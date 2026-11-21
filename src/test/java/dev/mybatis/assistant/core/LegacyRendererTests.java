package dev.mybatis.assistant.core;

import java.math.BigDecimal;
import java.util.*;

/** Behavior and failure boundaries of the standalone iBATIS evaluator. */
public final class LegacyRendererTests {
    private static int checks;
    private static void same(Object expected,Object actual){checks++;if(!Objects.equals(expected,actual))throw new AssertionError("expected "+expected+" got "+actual);}
    private static void yes(boolean value){checks++;if(!value)throw new AssertionError("condition failed");}
    private static void fails(String body,String json)throws Exception{
        checks++;try{run(body,json);throw new AssertionError("expected a failure");}catch(IllegalArgumentException expected){}
    }
    private static SqlRenderer.Result run(String body,String json)throws Exception{return SqlRenderer.render("<sqlMap namespace='old'><select id='q'>"+body+"</select></sqlMap>","q",json,List.of());}
    private static String sql(SqlRenderer.Result r){return r.sql().replaceAll("\\s+"," ").trim();}
    public static void main(String[] ignored)throws Exception{
        var query=run("SELECT * FROM users <dynamic prepend='WHERE'><isNotEmpty property='name' prepend='AND'> name=#name:VARCHAR# </isNotEmpty><isGreaterEqual property='age' compareValue='18' prepend='AND'> age&gt;=#age# </isGreaterEqual></dynamic>","{\"name\":\"张三\",\"age\":20}");
        same("SELECT * FROM users WHERE name=? AND age>=?",sql(query));same(List.of("张三",new BigDecimal(20)),query.parameters());
        same("SELECT * FROM users",sql(run("SELECT * FROM users <dynamic prepend='WHERE'><isNotEmpty property='name' prepend='AND'> name=#name# </isNotEmpty></dynamic>","{\"name\":\"\"}")));
        same("WHERE age=?",sql(run("<dynamic prepend='WHERE'><isNotNull property='name' prepend='AND'> name=#name# </isNotNull><isNotNull property='age' prepend='AND'> age=#age# </isNotNull></dynamic>","{\"age\":18}")));
        same("ANDname=?",sql(run("<dynamic removeFirstPrepend='true'><isNotNull property='name' prepend='AND'>name=#name#</isNotNull></dynamic>","{\"name\":\"a\"}"))); // dynamic without prepend does not strip child prepend
        var list=run("SELECT * FROM users WHERE id IN <iterate property='ids' open='(' close=')' conjunction=','>#ids[]#</iterate>","{\"ids\":[1,2,3]}");same("SELECT * FROM users WHERE id IN (?,?,?)",sql(list));same(List.of(new BigDecimal(1),new BigDecimal(2),new BigDecimal(3)),list.parameters());
        same("SELECT 1",sql(run("SELECT 1<iterate property='ids' prepend='WHERE' open='(' close=')' conjunction=','>#ids[]#</iterate>","{\"ids\":[]}")));
        same(List.of("a","b"),run("<iterate open='(' close=')' conjunction=','>#[].name#</iterate>","[{\"name\":\"a\"},{\"name\":\"b\"}]").parameters());
        same(List.of(new BigDecimal(1),new BigDecimal(2),new BigDecimal(3)),run("<iterate property='groups' conjunction=' OR '><iterate property='groups[].ids' open='(' close=')' conjunction=','>#groups[].ids[]#</iterate></iterate>","{\"groups\":[{\"ids\":[1,2]},{\"ids\":[3]}]}").parameters());
        same("(?,?)",sql(run("<iterate property='values' open='(' close=')' conjunction=','><isNotNull property='values[]'>#values[]#</isNotNull></iterate>","{\"values\":[1,null,2,null]}")));
        same("SELECT ?",sql(run("<isParameterPresent>SELECT #whatever#</isParameterPresent><isNotParameterPresent>SELECT 0</isNotParameterPresent>","42")));same(List.of(new BigDecimal(42)),run("SELECT #whatever#","42").parameters());
        same("SELECT 0",sql(run("<isParameterPresent>SELECT 1</isParameterPresent><isNotParameterPresent>SELECT 0</isNotParameterPresent>","null")));
        same("available",sql(run("<isPropertyAvailable property='name'>available</isPropertyAvailable><isNotPropertyAvailable property='name'>missing</isNotPropertyAvailable>","{\"name\":null}")));
        same("missing",sql(run("<isPropertyAvailable property='user.name'>available</isPropertyAvailable><isNotPropertyAvailable property='user.name'>missing</isNotPropertyAvailable>","{\"user\":{\"name\":\"a\"}}")));
        same("yes",sql(run("<isLessThan property='low' compareProperty='high'>yes</isLessThan>","{\"low\":1,\"high\":2}")));
        same("no",sql(run("<isEqual property='absent' compareValue='null'>yes</isEqual><isNotEqual property='absent' compareValue='null'>no</isNotEqual>","{}")));
        same("SELECT * FROM users ORDER BY name DESC",sql(run("SELECT * FROM users ORDER BY $order$","{\"order\":\"name DESC\"}")));
        same("SELECT #, $",sql(run("SELECT ##, $$","{}")));
        same(null,run("SELECT # name #","{\"name\":\"a\"}").parameters().getFirst());
        fails("SELECT #name,numericScale=-1#","{}");
        same(null,run("SELECT #value:INTEGER:-1#","{\"value\":-1}").parameters().getFirst());
        same(null,run("SELECT #value,jdbcType=INTEGER,nullValue=-1#","{\"value\":-1}").parameters().getFirst());
        var out=run("CALL p(#id#, #result,mode=OUT,jdbcType=INTEGER#)","{\"id\":1}");same(2,out.parameters().size());yes(!out.notes().isEmpty());
        String external="<sqlMap namespace='other'><sql id='columns'>id, name</sql></sqlMap>";
        same("SELECT id, name FROM users",sql(SqlRenderer.render("<sqlMap namespace='old'><select id='q'>SELECT <include refid='other.columns'/> FROM users</select></sqlMap>","q","{}",List.of(external))));
        var insert=SqlRenderer.render("<sqlMap><insert id='q'><selectKey keyProperty='id'>SELECT 99</selectKey>INSERT INTO users(name) VALUES(#name#)</insert></sqlMap>","q","{\"name\":\"a\"}",List.of());yes(!insert.sql().contains("SELECT 99"));yes(!insert.notes().isEmpty());
        var mapped=SqlRenderer.render("<sqlMap namespace='old'><parameterMap id='p' class='map'><parameter property='id' jdbcType='INTEGER'/><parameter property='name'/></parameterMap><select id='q' parameterMap='p'>SELECT * FROM users WHERE id=? AND name=?</select></sqlMap>","q","{\"id\":1,\"name\":\"a\"}",List.of());same(List.of(new BigDecimal(1),"a"),mapped.parameters());
        fails("<foreach collection='ids'>#ids#</foreach>","{}");fails("<isEqual property='name'>x</isEqual>","{}");fails("SELECT #name","{}");fails("<iterate property='ids'>#ids[]#</iterate>","{\"ids\":null}");fails("SELECT #ids[3]#","{\"ids\":[1]}");fails("SELECT #name,handler=some.ProjectHandler#","{}");
        checks++;try{SqlRenderer.render("<sqlMap namespace='old'><sql id='a'><include refid='a'/></sql><select id='q'><include refid='a'/></select></sqlMap>","q","{}",List.of());throw new AssertionError("cycle");}catch(IllegalArgumentException expected){}
        String huge="{\"ids\":["+String.join(",",Collections.nCopies(10_001,"1"))+"]}";fails("<iterate property='ids'>#ids[]#</iterate>",huge);
        System.out.println("PASS: "+checks+" standalone iBATIS renderer assertions");
    }
}
