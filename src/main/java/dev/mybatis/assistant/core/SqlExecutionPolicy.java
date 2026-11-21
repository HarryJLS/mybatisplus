package dev.mybatis.assistant.core;

import java.util.*;

/** Conservative execution gate, not a general SQL parser. Ambiguous dialect syntax is refused. */
public final class SqlExecutionPolicy {
    private SqlExecutionPolicy() {}
    public static String validate(String sql,SqlExecution.Mode mode){
        if(sql==null || sql.isBlank() || sql.length()>262144)throw new IllegalArgumentException("SQL 为空或超过 256 KiB");
        List<String> words=new ArrayList<>();int terminator=-1;
        for(int i=0;i<sql.length();){
            char c=sql.charAt(i);
            if(Character.isWhitespace(c)){i++;continue;}
            if(terminator>=0)throw new IllegalArgumentException("执行入口只允许一条 SQL");
            if(c=='-' && i+1<sql.length() && sql.charAt(i+1)=='-'){while(i<sql.length() && sql.charAt(i)!='\n')i++;continue;}
            if(c=='/' && i+1<sql.length() && sql.charAt(i+1)=='*'){
                if(i+2<sql.length() && Set.of('!','+').contains(sql.charAt(i+2)))throw new IllegalArgumentException("不执行方言指令或提示注释");
                int end=sql.indexOf("*/",i+2);if(end<0)throw new IllegalArgumentException("SQL 注释未闭合");i=end+2;continue;
            }
            if(c=='\'' || c=='"' || c=='`' || c=='['){
                char quote=c=='['?']':c;boolean closed=false;i++;
                while(i<sql.length()){
                    char next=sql.charAt(i++);
                    if(next=='\\')throw new IllegalArgumentException("不执行包含方言反斜杠转义的 SQL；请使用绑定参数");
                    if(next==quote){if(i<sql.length() && sql.charAt(i)==quote)i++;else{closed=true;break;}}
                }if(!closed)throw new IllegalArgumentException("SQL 引号未闭合");continue;
            }
            if(c=='$' || c=='#')throw new IllegalArgumentException("执行前必须完成参数/标识符展开");
            if(c==';'){terminator=i++;continue;}
            if(Character.isJavaIdentifierStart(c)){
                int start=i++;while(i<sql.length() && Character.isJavaIdentifierPart(sql.charAt(i)))i++;
                words.add(sql.substring(start,i).toUpperCase(Locale.ROOT));
            }else i++;
        }
        if(words.isEmpty())throw new IllegalArgumentException("没有可执行的 SQL");
        String first=words.getFirst();
        if(!Set.of("SELECT","INSERT","UPDATE","DELETE").contains(first))throw new IllegalArgumentException("只执行 SELECT/INSERT/UPDATE/DELETE；WITH、DDL、CALL 和事务语句未支持");
        Set<String> forbidden=Set.of("CREATE","ALTER","DROP","TRUNCATE","CALL","EXEC","EXECUTE","COMMIT","ROLLBACK","GRANT","REVOKE","INTO","OUTFILE","DUMPFILE","RETURNING");
        for(int i=1;i<words.size();i++){
            String word=words.get(i);
            if(forbidden.contains(word) && !(word.equals("INTO") && first.equals("INSERT")))throw new IllegalArgumentException("执行入口不支持语句中的 "+word);
            if(first.equals("SELECT") && Set.of("INSERT","UPDATE","DELETE","MERGE").contains(word))throw new IllegalArgumentException("查询模式不允许写入或锁定语句");
        }
        if(mode==SqlExecution.Mode.READ_ONLY && !first.equals("SELECT"))throw new IllegalArgumentException("查询模式仅允许 SELECT");
        return terminator<0?sql:sql.substring(0,terminator);
    }
}
