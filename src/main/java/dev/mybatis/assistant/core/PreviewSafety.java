package dev.mybatis.assistant.core;

import com.google.gson.*;
import java.math.*;
import java.util.*;
import java.util.regex.*;
import org.apache.ibatis.ognl.*;
import org.w3c.dom.*;

/** Closed JSON evaluation and conservative work/output bounds before MyBatis evaluates a script. */
final class PreviewSafety {
    private PreviewSafety() {}
    static final long MAX_OUTPUT=4*1024*1024,MAX_STEPS=100000,MAX_PARAMS=10000;
    private static final Pattern TOKENS=Pattern.compile("([#$])\\{([^}]+)}");
    private static final Set<String> METHODS=Set.of("size","isEmpty","length","get","contains","containsKey","containsValue","equals","startsWith","endsWith","trim","substring","toString");
    private static final Set<String> REFLECTION=Set.of("class","classLoader","declaringClass","protectionDomain","codeSource","declaredMethods","declaredFields","methods","fields","constructors","enumConstants","classes","module","TYPE");
    private static final class Input {long valueSize=1;int iterable=1,nodes;}
    private record Cost(long chars,long steps,long params) {}
    static void jsonText(String json){
        int depth=0;boolean quoted=false,escaped=false;
        for(char c:json.toCharArray()){
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;else if(c=='[' || c=='{'){if(++depth>64)throw new IllegalArgumentException("JSON 参数嵌套超过 64 层");}else if(c==']' || c=='}')depth--;
        }
    }
    static void script(org.w3c.dom.Node node,JsonElement json)throws OgnlException{script(node,json,new PreviewExpressions(()->{}));}
    static void script(org.w3c.dom.Node node,JsonElement json,PreviewExpressions expressions)throws OgnlException{
        Input input=new Input();json(json,input,0);inspect(node,input,0,expressions);estimate(node,input,0,expressions);
    }
    private static void json(JsonElement value,Input input,int depth){
        if(depth>64 || ++input.nodes>100000)throw new IllegalArgumentException("JSON 参数超过节点上限");
        if(value.isJsonArray()){int size=value.getAsJsonArray().size();if(size>10000)throw new IllegalArgumentException("集合超过 10000 项");input.iterable=Math.max(input.iterable,size);for(JsonElement child:value.getAsJsonArray())json(child,input,depth+1);}
        else if(value.isJsonObject()){int size=value.getAsJsonObject().size();if(size>10000)throw new IllegalArgumentException("Map 超过 10000 项");input.iterable=Math.max(input.iterable,size);for(var entry:value.getAsJsonObject().entrySet())json(entry.getValue(),input,depth+1);}
        else if(value.isJsonPrimitive()){
            if(value.getAsJsonPrimitive().isNumber())number(value.getAsBigDecimal());input.valueSize=Math.min(MAX_OUTPUT+1,input.valueSize+value.getAsString().length());
        }
    }
    private static void number(BigDecimal number){if(number.precision()>1000 || Math.abs((long)number.scale())>1000)throw new IllegalArgumentException("预览数值精度或 scale 超过 1000");}
    private static void inspect(org.w3c.dom.Node node,Input input,int depth,PreviewExpressions expressions)throws OgnlException{
        if(depth>64)throw new IllegalArgumentException("动态 SQL 嵌套超过 64 层");
        if(node instanceof Element element){
            String tag=element.getTagName();
            if(Set.of("if","when").contains(tag))expression(element.getAttribute("test"),input,expressions);
            if(tag.equals("foreach"))expression(element.getAttribute("collection"),input,expressions);
            if(tag.equals("bind"))expression(element.getAttribute("value"),input,expressions);
        }else if(node.getNodeType()==org.w3c.dom.Node.TEXT_NODE || node.getNodeType()==org.w3c.dom.Node.CDATA_SECTION_NODE){
            Matcher tokens=TOKENS.matcher(node.getNodeValue());while(tokens.find()){
                if(tokens.group(1).equals("$"))expression(tokens.group(2),input,expressions);
                else if(tokens.group(2).matches("(?s).*,\\s*(?:javaType|typeHandler)\\s*=.*"))throw new IllegalArgumentException("离线预览不加载自定义 javaType/typeHandler");
            }
        }
        for(org.w3c.dom.Node child=node.getFirstChild();child!=null;child=child.getNextSibling())inspect(child,input,depth+1,expressions);
    }
    private static int expression(String text,Input input,PreviewExpressions expressions)throws OgnlException{
        int nodes=expression(expressions.parse(text),input);if(input.valueSize*nodes>MAX_OUTPUT)throw new IllegalArgumentException("表达式的保守求值上界超过 4 MiB");return nodes;
    }
    private static int expression(org.apache.ibatis.ognl.Node node,Input input){
        String kind=node.getClass().getSimpleName();
        if(kind.endsWith("VarRef") || Set.of("ASTStaticMethod","ASTStaticField","ASTCtor","ASTAssign","ASTEval","ASTProject","ASTSelect","ASTSelectFirst","ASTSelectLast","ASTSequence").contains(kind))throw new IllegalArgumentException("离线预览不执行 Java 静态成员、构造器、反射或 OGNL 投影/赋值");
        if(node instanceof ASTMethod method && !METHODS.contains(method.getMethodName()))throw new IllegalArgumentException("离线预览未支持方法: "+method.getMethodName());
        if(node instanceof ASTConst constant && constant.getValue() instanceof Number value)number(new BigDecimal(value.toString()));
        if(node instanceof ASTConst constant && constant.getValue() instanceof org.apache.ibatis.ognl.Node)throw new IllegalArgumentException("离线预览不求值 OGNL lambda");
        if(node instanceof ASTProperty property && !property.isIndexedAccess() && property.jjtGetNumChildren()==1 && property.jjtGetChild(0) instanceof ASTConst value && REFLECTION.contains(String.valueOf(value.getValue())))throw new IllegalArgumentException("离线预览不读取反射属性");
        if(kind.equals("ASTList") || kind.equals("ASTMap"))input.iterable=Math.max(input.iterable,node.jjtGetNumChildren());
        int count=1;for(int i=0;i<node.jjtGetNumChildren();i++)count+=expression(node.jjtGetChild(i),input);return count;
    }
    private static Cost estimate(org.w3c.dom.Node node,Input input,int depth,PreviewExpressions expressions)throws OgnlException{
        long chars=0,steps=1,params=0;
        if(node.getNodeType()==org.w3c.dom.Node.TEXT_NODE || node.getNodeType()==org.w3c.dom.Node.CDATA_SECTION_NODE){
            String text=node.getNodeValue();chars=text.length();Matcher tokens=TOKENS.matcher(text);while(tokens.find())if(tokens.group(1).equals("#"))params++;else chars+=input.valueSize*expression(tokens.group(2),input,expressions);
        }
        if(node instanceof Element element){
            String tag=element.getTagName();String key=Set.of("if","when").contains(tag)?"test":tag.equals("foreach")?"collection":tag.equals("bind")?"value":null;
            if(key!=null)steps+=expression(element.getAttribute(key),input,expressions);
        }
        for(org.w3c.dom.Node child=node.getFirstChild();child!=null;child=child.getNextSibling()){Cost c=estimate(child,input,depth+1,expressions);chars+=c.chars();steps+=c.steps();params+=c.params();}
        if(node instanceof Element element && element.getTagName().equals("foreach")){
            chars=boundedMultiply(chars+element.getAttribute("separator").length(),input.iterable)+element.getAttribute("open").length()+element.getAttribute("close").length();steps=boundedMultiply(steps,input.iterable);params=boundedMultiply(params,input.iterable);
        }
        if(chars>MAX_OUTPUT || steps>MAX_STEPS || params>MAX_PARAMS)throw new IllegalArgumentException("动态 SQL 的保守展开上界超出限制（4 MiB/100000 步/10000 参数）");return new Cost(chars,steps,params);
    }
    private static long boundedMultiply(long value,int count){return count>0 && value>MAX_OUTPUT/count?MAX_OUTPUT+1:value*count;}
    static long parameterSize(Object value){
        return parameterSize(value,Collections.newSetFromMap(new IdentityHashMap<>()),0);
    }
    private static long parameterSize(Object value,Set<Object> active,int depth){
        if(depth>64)throw new IllegalArgumentException("参数展示嵌套超过 64 层");
        if(value instanceof Map<?,?> || value instanceof Collection<?>){
            if(!active.add(value))throw new IllegalArgumentException("参数展示存在循环引用");
            long size=0;
            if(value instanceof Map<?,?> map)for(var entry:map.entrySet()){size+=String.valueOf(entry.getKey()).length()+parameterSize(entry.getValue(),active,depth+1);if(size>MAX_OUTPUT)break;}
            else for(Object child:(Collection<?>)value){size+=parameterSize(child,active,depth+1);if(size>MAX_OUTPUT)break;}
            active.remove(value);return size;
        }
        if(value!=null && !(value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character))throw new IllegalArgumentException("预览参数仅支持 JSON 集合/Map 和标量");
        return String.valueOf(value).length()+16L;
    }
}
