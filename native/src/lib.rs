//! Request-scoped native parsing. No worker threads, timers, retained project data or I/O.
use jni::{objects::{JClass, JString}, sys::jstring, JNIEnv};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{borrow::Cow,collections::HashSet};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Column {
    pub name: String,
    pub sql_type: String,
    pub primary_key: bool,
    pub auto_increment: bool,
    pub nullable: bool,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Table { pub name: String, pub columns: Vec<Column> }

pub fn snake(input: &str) -> String {
    let chars: Vec<char> = input.chars().collect();
    let mut out = String::with_capacity(input.len() + 8);
    for (i, &c) in chars.iter().enumerate() {
        if c.is_uppercase() && i > 0 && (chars[i-1].is_lowercase() || chars[i-1].is_numeric()
            || (chars[i-1].is_uppercase() && chars.get(i+1).is_some_and(|n| n.is_lowercase()))) {
            out.push('_');
        }
        out.extend(c.to_lowercase());
    }
    out
}

/// Split outside strings, quoted identifiers, nested parentheses and SQL comments.
fn split_sql(input: &str, separator: u8) -> impl Iterator<Item=&str> {
    let b = input.as_bytes();
    let (mut depth, mut quote, mut start, mut i) = (0i32, 0u8, 0usize, 0usize);
    let mut finished=false;
    std::iter::from_fn(move || {
        if finished {return None;}
        while i < b.len() {
            let c = b[i];
            if quote != 0 {
                if c == b'\\' { i += 2; continue; }
                if c == quote {
                    if b.get(i+1) == Some(&quote) { i += 2; continue; }
                    quote = 0;
                }
            } else {
                match c {
                    b'\'' | b'"' | b'`' => quote = c,
                    b'[' => quote = b']',
                    b'(' => depth += 1,
                    b')' => depth -= 1,
                    _ if c == separator && depth == 0 => {
                        let part=input[start..i].trim();i+=1;start=i;return Some(part);
                    }
                    _ => (),
                }
            }
            i += 1;
        }
        finished=true;Some(input[start..].trim())
    })
}

fn identifier(input: &str) -> Result<(String, &str), String> {
    let s = input.trim_start();
    if s.is_empty() { return Err("缺少标识符".into()); }
    let first = s.as_bytes()[0];
    if matches!(first, b'`' | b'"' | b'[') {
        let end = if first == b'[' { ']' } else { first as char };
        let i = s[1..].find(end).ok_or("标识符引号未闭合")? + 1;
        Ok((s[1..i].into(), &s[i+1..]))
    } else {
        let i = s.find(|c: char| c.is_whitespace() || c == '(' || c == ',' || c == ')').unwrap_or(s.len());
        if i == 0 { return Err("标识符无效".into()); }
        Ok((s[..i].into(), &s[i..]))
    }
}

fn remove_comments(input: &str) -> Cow<'_,str> {
    if !input.contains("--") && !input.contains("/*") {return Cow::Borrowed(input);}
    let b = input.as_bytes(); let mut out = Vec::with_capacity(b.len());
    let (mut quote, mut i) = (0u8, 0usize);
    while i < b.len() {
        if quote != 0 {
            out.push(b[i]);
            if b[i] == b'\\' && i+1 < b.len() { i+=1; out.push(b[i]); }
            else if b[i] == quote {
                if b.get(i+1) == Some(&quote) { i+=1; out.push(b[i]); } else { quote=0; }
            }
        } else if b.get(i..i+2) == Some(b"--") {
            while i < b.len() && b[i] != b'\n' { i+=1; } out.push(b' '); continue;
        } else if b.get(i..i+2) == Some(b"/*") {
            i+=2; while i+1 < b.len() && &b[i..i+2] != b"*/" { i+=1; }
            i=(i+2).min(b.len()); out.push(b' '); continue;
        } else {
            if matches!(b[i], b'\'' | b'"' | b'`') { quote=b[i]; }
            out.push(b[i]);
        }
        i+=1;
    }
    Cow::Owned(String::from_utf8(out).expect("only ASCII comments were removed"))
}

pub fn parse_ddl(input: &str) -> Result<Table, String> {
    let clean = remove_comments(input);
    let pos = clean.as_bytes().windows(12).position(|part|part.eq_ignore_ascii_case(b"create table")).ok_or("请粘贴 CREATE TABLE 语句")? + 12;
    let mut rest = clean[pos..].trim();
    if rest.as_bytes().get(..13).is_some_and(|part|part.eq_ignore_ascii_case(b"if not exists")) { rest=rest[13..].trim(); }
    let (mut name, mut rest) = identifier(rest)?;
    // Qualified schema.table, including quoted names.
    if rest.trim_start().starts_with('.') {
        (name, rest) = identifier(&rest.trim_start()[1..])?;
    } else if name.contains('.') { name = name.rsplit('.').next().unwrap().to_owned(); }
    rest=rest.trim_start();
    if !rest.starts_with('(') { return Err("表定义缺少左括号".into()); }
    let b=rest.as_bytes(); let (mut depth,mut quote,mut end,mut i)=(0i32,0u8,None,0usize);
    while i < b.len() {
        let c=b[i];
        if quote!=0 {
            if c==b'\\' { i+=2; continue; }
            if c==quote {
                if b.get(i+1)==Some(&quote) { i+=2; continue; }
                quote=0;
            }
        } else {
            match c {
                b'\''|b'"'|b'`' => quote=c,
                b'(' => depth+=1,
                b')' => { depth-=1; if depth==0 { end=Some(i); break; } },
                _ => (),
            }
        } i+=1;
    }
    let end=end.ok_or("表定义括号或字符串未闭合")?;
    let mut columns=Vec::new(); let mut primary=HashSet::new();
    for part in split_sql(&rest[1..end], b',') {
        if part.is_empty() { continue; }
        let (word,tail)=identifier(part)?;
        let key=word.to_ascii_lowercase();
        if key=="primary" || key=="constraint" && tail.to_ascii_lowercase().contains("primary key") {
            if let (Some(a),Some(z))=(part.find('('),part.rfind(')')) {
                for col in split_sql(&part[a+1..z],b',') { primary.insert(identifier(col)?.0); }
            } continue;
        }
        if ["constraint","unique","key","index","foreign","check","fulltext","spatial"].contains(&key.as_str()) { continue; }
        if columns.len()>=2000 {return Err("单表字段数量超过 2000".into());}
        let tail=tail.trim();
        if tail.is_empty() { return Err(format!("字段 {word} 缺少类型")); }
        let mut depth=0; let mut end=tail.len();
        for (i,c) in tail.char_indices() {
            if c=='(' {depth+=1;} else if c==')' {depth-=1;}
            else if c.is_whitespace() && depth==0 {end=i;break;}
        }
        let typ=tail[..end].to_ascii_lowercase(); let attrs=tail[end..].to_ascii_lowercase();
        columns.push(Column {
            name:word,sql_type:typ,primary_key:attrs.contains("primary key"),
            auto_increment:attrs.contains("auto_increment") || attrs.contains("identity") || tail.starts_with("serial") || tail.starts_with("bigserial"),
            nullable:!attrs.contains("not null") && !attrs.contains("primary key"),
        });
    }
    for col in &mut columns { if primary.contains(&col.name) {col.primary_key=true;col.nullable=false;} }
    if columns.is_empty() { return Err("未解析到字段".into()); }
    Ok(Table{name,columns})
}

pub fn sql_literal(value: &str, typ: &str) -> Result<String,String> {
    if value=="null" && typ.is_empty() {return Ok("NULL".into());}
    match typ {
        "Byte"|"Short"|"Integer"|"Long"|"Float"|"Double"|"BigDecimal"|"BigInteger" => {
            if !value.is_empty() && value.chars().all(|c| c.is_ascii_digit() || "+-.eE".contains(c)) {
                Ok(value.into())
            } else {Err(format!("数值参数无效: {value}"))}
        }
        "Boolean" => match value {"true"=>Ok("TRUE".into()),"false"=>Ok("FALSE".into()),_=>Err("布尔参数无效".into())},
        _ => Ok(format!("'{}'",value.replace('\'',"''"))),
    }
}

pub fn substitute(sql:&str,params:&[String]) -> Result<String,String> {
    let b=sql.as_bytes(); let mut out=Vec::with_capacity(sql.len()+params.len()*12);
    let (mut i,mut quote,mut p)=(0usize,0u8,0usize);
    while i<b.len() {
        let c=b[i];
        if quote!=0 {
            out.push(c);
            if c==b'\\' && i+1<b.len() {i+=1;out.push(b[i]);}
            else if c==quote {
                if b.get(i+1)==Some(&quote) {i+=1;out.push(b[i]);} else {quote=0;}
            }
        } else if b.get(i..i+2)==Some(b"--") {
            while i<b.len() && b[i]!=b'\n' {out.push(b[i]);i+=1;} continue;
        } else if b.get(i..i+2)==Some(b"/*") {
            out.extend_from_slice(b"/*");i+=2;
            while i<b.len() {out.push(b[i]);i+=1;if i>=2 && &b[i-2..i]==b"*/" {break;}} continue;
        } else if c==b'?' {
            let value=params.get(p).ok_or("SQL 占位符多于日志参数")?;
            out.extend_from_slice(value.as_bytes());p+=1;
        } else {if matches!(c,b'\''|b'"'|b'`') {quote=c;} out.push(c);}
        i+=1;
    }
    if p!=params.len() {return Err(format!("SQL 有 {p} 个占位符，但日志有 {} 个参数",params.len()));}
    String::from_utf8(out).map_err(|e|e.to_string())
}

fn parse_params(line:&str)->Result<Vec<String>,String> {
    let mut result=Vec::new(); let mut start=0; let b=line.as_bytes(); let mut i=0;
    // MyBatis logs are unescaped: only split after a recognized '(Type)' suffix.
    while i<b.len() {
        if b[i]==b')' && (i+1==b.len() || b.get(i+1)==Some(&b',')) {
            if let Some(a)=line[start..i].rfind('(') {
                let a=start+a; let typ=&line[a+1..i];
                if !typ.is_empty() && typ.chars().all(|c|c.is_alphanumeric() || c=='.' || c=='[' || c==']') {
                    result.push(sql_literal(line[start..a].trim(),typ)?);
                    start=i+1;if b.get(start)==Some(&b',') {start+=1;}
                }
            }
        } else if line[start..].trim_start().starts_with("null") {
            let n=start+line[start..].len()-line[start..].trim_start().len()+4;
            if n==b.len() || b.get(n)==Some(&b',') {
                result.push("NULL".into());start=n;if b.get(start)==Some(&b','){start+=1;} i=start;continue;
            }
        }
        i+=1;
    }
    if !line[start..].trim().is_empty() {return Err("参数日志格式无法可靠解析，请检查 (Type) 后缀；多线程日志请先按线程筛选".into());}
    Ok(result)
}

pub fn logs_to_sql(input:&str)->Result<String,String> {
    let (mut pending,mut context)=(None,None);let mut out=String::new();
    for line in input.lines() {
        if let Some(pos)=line.find("Preparing:") {
            if pending.is_some() {return Err("发现交错的 Preparing 日志，请按线程筛选后重试".into());}
            pending=Some(line[pos+10..].trim().to_owned());
            context=Some(log_thread(&line[..pos]));
        } else if let Some(pos)=line.find("Parameters:") {
            let sql=pending.take().ok_or("Parameters 日志前缺少 Preparing" )?;
            let thread=log_thread(&line[..pos]);
            if context.flatten().is_some_and(|t|Some(t)!=thread) {return Err("日志线程不一致，请先按线程筛选".into());}
            let rendered=substitute(&sql,&parse_params(line[pos+11..].trim())?)?;
            if !out.is_empty() {out.push_str("\n\n");}
            out.push_str(rendered.trim_end_matches(';'));out.push(';');
        }
    }
    if pending.is_some() {return Err("Preparing 日志缺少 Parameters 行".into());}
    if out.is_empty() {return Err("未找到 Preparing / Parameters 日志".into());}
    Ok(out)
}
fn log_thread(prefix:&str)->Option<&str> {
    prefix.find('[').and_then(|a|prefix[a+1..].find(']').map(|z|&prefix[a+1..a+1+z]))
}

pub fn process(input:&str)->String {
    let result=(|| -> Result<Value,String> {
        if input.len()>8*1024*1024 {return Err("输入超过 8 MiB 限制".into());}
        let req:Value=serde_json::from_str(input).map_err(|e|e.to_string())?;
        let text=req["text"].as_str().ok_or("缺少 text")?;
        match req["operation"].as_str() {
            Some("ddl") => Ok(json!(parse_ddl(text)?)),
            Some("logs") => Ok(json!(logs_to_sql(text)?)),
            Some("snake") => Ok(json!(snake(text))),
            _=>Err("未知 operation".into()),
        }
    })();
    match result {Ok(data)=>json!({"ok":true,"data":data}).to_string(),Err(error)=>json!({"ok":false,"error":error}).to_string()}
}

#[no_mangle]
pub extern "system" fn Java_dev_mybatis_assistant_core_NativeBridge_request(mut env:JNIEnv,_class:JClass,input:JString)->jstring {
    // No Rust unwind is allowed across JNI. A malformed request becomes a structured error.
    let response=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let input:String=env.get_string(&input).map_err(|e|e.to_string())?.into();
        Ok::<_,String>(process(&input))
    }));
    let output=match response {
        Ok(Ok(s))=>s,Ok(Err(e))=>json!({"ok":false,"error":e}).to_string(),
        Err(_)=>json!({"ok":false,"error":"native parser panic"}).to_string(),
    };
    match env.new_string(output) {Ok(s)=>s.into_raw(),Err(_)=>std::ptr::null_mut()}
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn ddl_nested() {
        let t=parse_ddl("CREATE TABLE `user` (`id` bigint NOT NULL AUTO_INCREMENT, name varchar(50) DEFAULT 'a,b', amount decimal(10, 2), PRIMARY KEY (`id`))").unwrap();
        assert_eq!(t.columns.len(),3);assert!(t.columns[0].primary_key);assert_eq!(t.columns[2].sql_type,"decimal(10, 2)");
    }
    #[test] fn ddl_quoted_schema() {assert_eq!(parse_ddl("create table \"public\".\"orders\" (id bigint primary key)").unwrap().name,"orders");}
    #[test] fn ddl_comments() {assert_eq!(parse_ddl("-- hi\nCREATE TABLE x (id int, /* xxx, */ value text default '--,hi')").unwrap().columns.len(),2);}
    #[test] fn ddl_composite() {assert!(parse_ddl("create table x (a int, b int, constraint pk primary key(a,b))").unwrap().columns.iter().all(|c|c.primary_key));}
    #[test] fn ddl_invalid() {assert!(parse_ddl("create table x (id int").is_err());assert!(parse_ddl("drop table x").is_err());}
    #[test] fn snake_acronym() {assert_eq!(snake("URLValue"),"url_value");assert_eq!(snake("userId"),"user_id");}
    #[test] fn logs_strings() {assert_eq!(logs_to_sql("==> Preparing: select * from user where id = ? and name = ?\n==> Parameters: 12(Long), O'Reilly, 张三(String)").unwrap(),"select * from user where id = 12 and name = 'O''Reilly, 张三';");}
    #[test] fn logs_null() {assert_eq!(logs_to_sql("Preparing: select ?, ?, ?\nParameters: null, true(Boolean), 1.25(BigDecimal)").unwrap(),"select NULL, TRUE, 1.25;");}
    #[test] fn logs_no_params() {assert_eq!(logs_to_sql("Preparing: select 1\nParameters:").unwrap(),"select 1;");}
    #[test] fn logs_literal_question() {assert_eq!(substitute("select '?' -- ?\n, ? /* ? */",&["1".into()]).unwrap(),"select '?' -- ?\n, 1 /* ? */");}
    #[test] fn logs_mismatch() {assert!(logs_to_sql("Preparing: select ?, ?\nParameters: 1(Integer)").is_err());}
    #[test] fn logs_interleaved() {assert!(logs_to_sql("Preparing: select ?\nPreparing: select ?\nParameters: 1(Integer)").is_err());}
    #[test] fn logs_thread() {assert!(logs_to_sql("[one] Preparing: select ?\n[two] Parameters: 1(Integer)").is_err());}
    #[test] fn logs_unicode() {assert_eq!(logs_to_sql("Preparing: select ? as 中文\nParameters: 你好(String)").unwrap(),"select '你好' as 中文;");}
    #[test] fn malformed_request() {assert!(process("bad json").contains("false"));}
    #[test] fn ddl_streams_quoted_nested_unicode_columns() {
        let table=parse_ddl("CrEaTe TaBlE IF NOT EXISTS x (`名字` varchar(20) default 'a,b', id decimal(10,2), PRIMARY KEY (`名字`,id))").unwrap();
        assert_eq!(table.columns.len(),2);assert_eq!(table.columns[0].name,"名字");assert!(table.columns.iter().all(|column|column.primary_key));
    }
    #[test] fn ddl_rejects_column_limit_while_streaming() {
        let columns=(0..2001).map(|i|format!("c{i} int")).collect::<Vec<_>>().join(",");
        assert_eq!(parse_ddl(&format!("CREATE TABLE x ({columns})")).unwrap_err(),"单表字段数量超过 2000");
    }
    #[test] fn logs_stream_multiple_pairs() {
        assert_eq!(logs_to_sql("Preparing: select ?;\nParameters: 1(Integer)\nPreparing: select ?\nParameters: 中文(String)").unwrap(),"select 1;\n\nselect '中文';");
    }
}
