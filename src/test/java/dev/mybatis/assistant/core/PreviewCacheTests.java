package dev.mybatis.assistant.core;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLIncludeTransformer;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.*;
import org.apache.ibatis.scripting.xmltags.XMLScriptBuilder;
import org.apache.ibatis.session.Configuration;

/** Original MyBatis is the oracle; reflection only reads its cache for regression tests. */
public final class PreviewCacheTests {
    private static int checks;
    private static SqlRenderer.Result oracle(String xml,String json)throws Exception{
        Configuration configuration=new Configuration();XPathParser parser=new XPathParser(SqlRenderer.parseXml(xml),false);
        String namespace=parser.evalString("/mapper/@namespace");
        for(XNode fragment:parser.evalNodes("/mapper/sql"))configuration.getSqlFragments().put(namespace+"."+fragment.getStringAttribute("id"),fragment);
        XNode statement=parser.evalNode("/mapper/select");MapperBuilderAssistant assistant=new MapperBuilderAssistant(configuration,"oracle");assistant.setCurrentNamespace(namespace);
        new XMLIncludeTransformer(configuration,assistant).applyIncludes(statement.getNode());
        Map<String,Object> parameters=map(JsonParser.parseString(json).getAsJsonObject());
        BoundSql bound=new XMLScriptBuilder(configuration,statement,Map.class).parseScriptNode().getBoundSql(parameters);
        List<Object> values=new ArrayList<>();
        for(ParameterMapping parameter:bound.getParameterMappings())values.add(bound.hasAdditionalParameter(parameter.getProperty())?bound.getAdditionalParameter(parameter.getProperty()):configuration.newMetaObject(parameters).getValue(parameter.getProperty()));
        return new SqlRenderer.Result(bound.getSql(),values);
    }
    private static Map<String,Object> map(JsonObject json){Map<String,Object> result=new LinkedHashMap<>();json.entrySet().forEach(entry->result.put(entry.getKey(),value(entry.getValue())));return result;}
    private static Object value(JsonElement json){
        if(json.isJsonNull())return null;
        if(json.isJsonObject())return map(json.getAsJsonObject());
        if(json.isJsonArray()){List<Object> result=new ArrayList<>();json.getAsJsonArray().forEach(item->result.add(value(item)));return result;}
        if(json.getAsJsonPrimitive().isBoolean())return json.getAsBoolean();
        if(json.getAsJsonPrimitive().isNumber())return json.getAsBigDecimal();return json.getAsString();
    }
    private static void compare(String body,String json)throws Exception{
        String xml="<mapper namespace='probe.Mapper'>"+body+"</mapper>";
        var expected=oracle(xml,json);var actual=SqlRenderer.render(xml,"find",json,List.of());checks++;
        if(!expected.sql().equals(actual.sql()) || !expected.parameters().equals(actual.parameters()))throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args)throws Exception{
        compare("<select id='find'>SELECT #{value}</select>","{\"value\":7}");
        for(String json:List.of("{\"value\":0}","{\"value\":1}","{\"value\":null}","{\"value\":\"\"}"))compare("<select id='find'>SELECT 1 <where><if test='value'>AND id=#{value}</if></where></select>",json);
        compare("<select id='find'>UPDATE x <set><if test='name != null'>name=#{name},</if><if test='id > 0'>id=#{id},</if></set></select>","{\"id\":3,\"name\":\"x\"}");
        compare("<select id='find'>SELECT <trim prefix='(' suffix=')' suffixOverrides=','>#{id},#{name},</trim></select>","{\"id\":3,\"name\":\"x\"}");
        for(String json:List.of("{\"ids\":[1,2,3]}","{\"ids\":[]}","{\"ids\":null}"))compare("<select id='find'>SELECT 1 <foreach collection='ids' item='id' index='i' nullable='true' open='(' separator=',' close=')'>#{i}:#{id}</foreach></select>",json);
        compare("<select id='find'>SELECT <foreach collection='rows' item='row' separator=','><foreach collection='row' item='v' separator=';'>#{v}</foreach></foreach></select>","{\"rows\":[[1,2],[3,4]]}");
        compare("<select id='find'>SELECT <foreach collection='ids' item='v' separator=','><if test='v > 1'>#{v}</if></foreach></select>","{\"ids\":[0,1,2,3]}");
        compare("<select id='find'>SELECT <foreach collection='entries' item='v' index='k' separator=','>#{k}=#{v}</foreach></select>","{\"entries\":{\"a\":1,\"b\":2}}");
        compare("<select id='find'><bind name='pattern' value='&quot;%&quot; + name + &quot;%&quot;'/><choose><when test='name != null'>SELECT #{pattern}</when><otherwise>SELECT 0</otherwise></choose></select>","{\"name\":\"x\"}");
        compare("<select id='find'>SELECT ${name},${missing},${ids.size} <if test='name.length() > 0'>,#{name}</if></select>","{\"name\":\"column\",\"ids\":[1,2]}");
        compare("<sql id='body'><foreach collection='ids' item='v' separator=','>#{v}</foreach></sql><select id='find'>SELECT <include refid='body'/> <include refid='body'/></select>","{\"ids\":[1,2]}");
        Field field=Class.forName("org.apache.ibatis.scripting.xmltags.OgnlCache").getDeclaredField("expressionCache");field.setAccessible(true);
        Map<?,?> cache=(Map<?,?>)field.get(null);int before=cache.size();
        for(int i=0;i<2000;i++){
            String xml="<mapper namespace='probe.Mapper'><select id='find'><bind name='a' value='id + "+i+"'/><if test='id != null and "+i+" == "+i+"'>SELECT ${a},#{id}</if></select></mapper>";
            SqlRenderer.render(xml,"find","{\"id\":1}",List.of());
        }
        checks++;if(cache.size()!=before)throw new AssertionError("Preview grew MyBatis static cache: "+before+" -> "+cache.size());
        PreviewExpressions expressions=new PreviewExpressions(()->{});for(int i=0;i<1000;i++)expressions.parse("id == "+i);
        Field nodes=PreviewExpressions.class.getDeclaredField("nodes");nodes.setAccessible(true);checks++;
        if(((Map<?,?>)nodes.get(expressions)).size()>256)throw new AssertionError("Request cache exceeded capacity");
        try{SqlRenderer.render("<mapper namespace='probe.Mapper'><select id='find'>SELECT 1</select></mapper>","find","{}",List.of(),()->{throw new java.util.concurrent.CancellationException();});throw new AssertionError("ignored cancellation");}
        catch(java.util.concurrent.CancellationException expected){checks++;}
        System.out.println("PASS: "+checks+" preview cache/MyBatis oracle assertions; 2000 unique renders added zero static cache entries");
    }
}
