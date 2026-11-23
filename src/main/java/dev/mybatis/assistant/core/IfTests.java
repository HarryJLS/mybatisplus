package dev.mybatis.assistant.core;

import java.util.*;

/** Build null guards without treating dots inside a Map key as bean properties. */
public final class IfTests {
    private IfTests() {}
    public static String condition(String sql){
        return condition(sql,path->"size");
    }
    public static String condition(String sql,java.util.function.Function<String,String> lengthProperty){
        var problems=SqlParameters.inspect(sql);if(!problems.isEmpty())throw new IllegalArgumentException("请先修复参数绑定: "+problems.getFirst().message());
        Set<String> guards=new LinkedHashSet<>();
        for(var token:SqlParameters.tokens(sql)){
            if(token.dollar())continue;
            String path;
            try{path=new org.apache.ibatis.builder.ParameterExpression(token.expression()).get("property");}
            catch(RuntimeException e){throw new IllegalArgumentException("请先修复 #{} 参数语法");}
            if(path==null || !path.matches("[\\p{L}_][\\p{L}\\p{N}_]*(?:(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)|(?:\\[(?:[0-9]+|'[^'\\r\\n]*'|\"[^\"\\r\\n]*\")]))*"))throw new IllegalArgumentException("无法为此参数安全生成 if test: "+path);
            int depth=0;char quote=0;
            for(int i=0;i<path.length();i++){
                char c=path.charAt(i);
                if(quote!=0){if(c==quote)quote=0;continue;}
                if(c=='\'' || c=='"'){quote=c;continue;}
                if(c=='['){guards.add(path.substring(0,i)+" != null");depth++;int end=path.indexOf(']',i);String index=path.substring(i+1,end);if(index.matches("[0-9]+")){String length=lengthProperty.apply(path.substring(0,i));if(length==null)throw new IllegalArgumentException("无法确定索引参数是数组还是 List: "+path);guards.add(path.substring(0,i)+"."+length+" > "+index);}}
                else if(c==']')depth--;
                else if(c=='.' && depth==0)guards.add(path.substring(0,i)+" != null");
            }
            guards.add(path+" != null");
        }
        if(guards.isEmpty())throw new IllegalArgumentException("选中 SQL 中没有 #{} 参数");
        return String.join(" and ",guards);
    }
}
