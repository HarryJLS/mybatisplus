package dev.mybatis.assistant.core;

import java.util.*;
import org.apache.ibatis.builder.ParameterExpression;
import org.apache.ibatis.type.JdbcType;

/** Bounded editing checks. This never evaluates an expression or reads a database. */
public final class SqlParameters {
    private SqlParameters() {}
    public static final List<String> OPTIONS=List.of("jdbcType","javaType","typeHandler","mode","numericScale","resultMap","jdbcTypeName");
    public record Token(int start,int end,String expression,boolean dollar,boolean quoted,boolean commented) {}
    public record Issue(int start,int end,String message) {}
    public static List<Token> tokens(String text){
        if(text.length()>262144)return List.of();
        List<Token> result=new ArrayList<>();char quote=0;boolean lineComment=false,blockComment=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if((c=='#' || c=='$') && i+1<text.length() && text.charAt(i+1)=='{'){
                int end=text.indexOf('}',i+2);if(end<0)end=text.length();
                result.add(new Token(i,end<text.length()?end+1:end,text.substring(i+2,end),c=='$',quote!=0,lineComment || blockComment));i=end;continue;
            }
            if(lineComment){if(c=='\n' || c=='\r')lineComment=false;continue;}
            if(blockComment){if(c=='*' && i+1<text.length() && text.charAt(i+1)=='/'){blockComment=false;i++;}continue;}
            if(quote==0 && c=='-' && i+1<text.length() && text.charAt(i+1)=='-'){lineComment=true;i++;continue;}
            if(quote==0 && c=='/' && i+1<text.length() && text.charAt(i+1)=='*'){blockComment=true;i++;continue;}
            if(quote!=0){if(c=='\\')i++;else if(c==quote){if(i+1<text.length() && text.charAt(i+1)==quote)i++;else quote=0;}}
            else if(c=='\'' || c=='"' || c=='`')quote=c;
        }
        return List.copyOf(result);
    }
    public static List<Issue> inspect(String text){
        List<Issue> result=new ArrayList<>();
        for(Token token:tokens(text)){
            String message=null;
            if(token.end()==text.length() && !text.endsWith("}"))message="SQL 参数占位符缺少 }";
            else if(token.expression().isBlank())message="SQL 参数占位符不能为空";
            else if(!token.dollar()){
                try{
                    var options=new ParameterExpression(token.expression());
                    if(options.containsKey("expression"))message="MyBatis #{} 不支持表达式；请使用 bind 后绑定变量";
                    else if(options.getOrDefault("property","").isBlank())message="#{} 缺少参数属性";
                    for(var entry:options.entrySet()){
                        String key=entry.getKey(),value=entry.getValue();
                        if(!key.equals("property") && !key.equals("expression") && !OPTIONS.contains(key))message="未知 #{} 选项: "+key;
                        if(key.equals("jdbcType"))try{JdbcType.valueOf(value);}catch(IllegalArgumentException e){message="无效 jdbcType: "+value;}
                        if(key.equals("mode") && !Set.of("IN","OUT","INOUT").contains(value))message="无效参数 mode: "+value;
                        if(key.equals("numericScale"))try{if(Integer.parseInt(value)<0)message="numericScale 不能为负数";}catch(NumberFormatException e){message="numericScale 必须为整数";}
                    }
                }catch(RuntimeException e){message="#{} 参数选项语法错误";}
                if(token.quoted())result.add(new Issue(token.start(),token.end(),"#{} 位于 SQL 引号内，? 不会成为 JDBC 绑定参数；LIKE 请使用 CONCAT 或 bind"));
                if(token.commented())result.add(new Issue(token.start(),token.end(),"#{} 位于 SQL 注释内，MyBatis 参数数量与 JDBC 占位符可能不一致"));
            }
            if(message!=null)result.add(new Issue(token.start(),token.end(),message));
        }
        return List.copyOf(result);
    }
}
