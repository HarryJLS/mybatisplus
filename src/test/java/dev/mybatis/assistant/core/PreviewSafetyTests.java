package dev.mybatis.assistant.core;

import java.util.*;

public final class PreviewSafetyTests {
    private static int count;
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void reject(Work work)throws Exception{count++;try{work.run();}catch(IllegalArgumentException|org.apache.ibatis.ognl.OgnlException expected){return;}throw new AssertionError("unsafe/unbounded evaluation accepted");}
    private static String mapper(String sql){return "<mapper namespace='safe.Mapper'><select id='q'>"+sql+"</select></mapper>";}
    public static void main(String[] args)throws Exception{
        reject(()->SqlRenderer.render(mapper("SELECT ${@java.lang.System@getProperty('user.home')}"),"q","{}",List.of()));
        reject(()->SqlRenderer.render(mapper("<bind name='x' value='new java.lang.String()'/>SELECT #{x}"),"q","{}",List.of()));
        reject(()->SqlRenderer.render(mapper("<if test='_parameter.class != null'>SELECT 1</if>"),"q","{}",List.of()));
        reject(()->SqlRenderer.render(mapper("<bind name='x' value='#root'/>SELECT #{x}"),"q","{}",List.of()));
        reject(()->SqlRenderer.render(mapper("SELECT #{x,typeHandler=example.Handler}"),"q","{\"x\":1}",List.of()));
        reject(()->SqlRenderer.render(mapper("SELECT #{x}"),"q","{\"x\":1e1000000}",List.of()));
        String values="["+String.join(",",Collections.nCopies(101,"1"))+"]";
        reject(()->SqlRenderer.render(mapper("<foreach collection='ids' item='a'><foreach collection='ids' item='b'>#{a}</foreach></foreach>"),"q","{\"ids\":"+values+"}",List.of()));
        reject(()->SqlRenderer.render("<mapper namespace='safe.Mapper'><sql id='a'><include refid='${fragment}'/></sql><select id='q'><include refid='a'/></select></mapper>","q","{}",List.of()));
        var good=SqlRenderer.render(mapper("<bind name='pattern' value=\"'%' + name + '%'\"/>SELECT #{pattern}<if test='ids != null and ids.size() > 0'>WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></if>"),"q","{\"name\":\"Alice\",\"ids\":[1,2]}",List.of());
        count++;if(!good.parameters().getFirst().equals("%Alice%"))throw new AssertionError(good.parameters());
        System.out.println("PASS: "+count+" bounded/offline preview assertions");
    }
}
