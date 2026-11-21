package dev.mybatis.assistant.core;

import java.util.*;
import java.util.regex.*;

/** Small, deterministic subset of Spring Data naming; unsupported names fail explicitly. */
public final class MethodQuery {
    private MethodQuery() {}
    private static final Pattern NAME=Pattern.compile("^(find|select|count|exists)By(.+)$");
    private static final LinkedHashMap<String,String> OPERATORS=new LinkedHashMap<>();
    static {
        OPERATORS.put("IsNotNull","IS NOT NULL"); OPERATORS.put("IsNull","IS NULL");
        OPERATORS.put("GreaterThanEqual",">="); OPERATORS.put("LessThanEqual","<=");
        OPERATORS.put("GreaterThan",">"); OPERATORS.put("LessThan","<");
        OPERATORS.put("NotLike","NOT LIKE");OPERATORS.put("Like","LIKE");
        OPERATORS.put("NotIn","NOT IN"); OPERATORS.put("In","IN");
        OPERATORS.put("Not","!=");
    }
    public static String generate(String method,String table,String resultType,Map<String,String> parameters,boolean mysql) {
        Matcher m=NAME.matcher(method);
        if(!m.matches()) throw new IllegalArgumentException("支持 find/select/count/existsBy…，例如 findByNameAndAgeGreaterThan");
        String expression=m.group(2), order=""; int orderIndex=expression.indexOf("OrderBy");
        if(orderIndex>=0) {order=expression.substring(orderIndex+7);expression=expression.substring(0,orderIndex);}
        String[] terms=expression.split("(?<=.)(And|Or)(?=[A-Z])");
        Matcher connectors=Pattern.compile("(And|Or)(?=[A-Z])").matcher(expression);
        List<String> clauses=new ArrayList<>();
        for(String term:terms) {
            String op="=", field=term;
            for(var entry:OPERATORS.entrySet()) if(term.endsWith(entry.getKey())) {
                field=term.substring(0,term.length()-entry.getKey().length());op=entry.getValue();break;
            }
            if(field.isEmpty()) throw new IllegalArgumentException("方法名缺少字段");
            String property=Character.toLowerCase(field.charAt(0))+field.substring(1);
            String column=Schema.xml(Schema.quote(Schema.snake(property),mysql));
            String clause=column+" "+Schema.xml(op);
            if(!op.startsWith("IS ")) {
                String binding=parameters.get(property);
                if(binding==null) throw new IllegalArgumentException("缺少参数 "+property+"；请为对应参数添加 @Param(\""+property+"\")");
                if(op.endsWith("IN")) clause+=" <foreach collection=\""+Schema.xml(binding)+"\" item=\"item\" open=\"(\" separator=\",\" close=\")\">#{item}</foreach>";
                else clause+=" #{"+binding+"}";
            }
            if(!clauses.isEmpty() && connectors.find()) clauses.add(connectors.group(1).equals("And")?"AND":"OR");
            clauses.add(clause);
        }
        String projection=m.group(1).equals("count")?"COUNT(*)":m.group(1).equals("exists")?"CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END":"*";
        String type=m.group(1).equals("count")?"long":m.group(1).equals("exists")?"boolean":resultType;
        if(type==null || type.isBlank()) throw new IllegalArgumentException("无法确定返回实体类型");
        StringBuilder out=new StringBuilder("<select id=\""+method+"\" resultType=\""+Schema.xml(type)+"\">\n  SELECT "+projection+" FROM "+Schema.xml(Schema.quote(table,mysql))+"\n  WHERE "+String.join(" ",clauses));
        if(!order.isEmpty()) {
            Matcher sort=Pattern.compile("([A-Z][A-Za-z0-9]*?)(Asc|Desc)(?=[A-Z]|$)").matcher(order);
            List<String> sorts=new ArrayList<>();int end=0;
            while(sort.find()) {if(sort.start()!=end) throw new IllegalArgumentException("OrderBy 格式无效");sorts.add(Schema.xml(Schema.quote(Schema.snake(sort.group(1)),mysql))+" "+sort.group(2).toUpperCase(Locale.ROOT));end=sort.end();}
            if(end!=order.length()) throw new IllegalArgumentException("排序必须包含 Asc 或 Desc");
            out.append("\n  ORDER BY ").append(String.join(", ",sorts));
        }
        return out.append("\n</select>").toString();
    }
}
