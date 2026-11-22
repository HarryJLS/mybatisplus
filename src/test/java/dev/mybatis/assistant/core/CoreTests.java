package dev.mybatis.assistant.core;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** Executable checks for JNI, generation contracts and actual MyBatis dynamic evaluation. */
public final class CoreTests {
    private static int checks;
    private static void equal(Object actual,Object expected){checks++;if(!Objects.equals(actual,expected))throw new AssertionError("expected "+expected+" but got "+actual);}
    private static void yes(boolean actual){checks++;if(!actual)throw new AssertionError("condition failed");}
    private static void fails(Throwing runnable){checks++;try{runnable.run();}catch(Exception|LinkageError e){return;}throw new AssertionError("expected failure");}
    @FunctionalInterface interface Throwing{void run()throws Exception;}
    public static void main(String[] args)throws Exception{
        nativeAndGeneration();queries();dynamic();index();jniBenchmark();LegacyRendererTests.main(new String[0]);EditorCoreTests.main(new String[0]);RuntimeCoreTests.main(new String[0]);PreviewSafetyTests.main(new String[0]);PreviewCacheTests.main(new String[0]);
        System.out.println("PASS: "+checks+" Java/JNI/MyBatis assertions");
    }
    private static void nativeAndGeneration()throws Exception{
        var table=NativeBridge.parseDdl("CREATE TABLE users (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_name VARCHAR(100) NOT NULL, age INT, created_at TIMESTAMP)");
        equal(table.name(),"users");equal(table.columns().size(),4);equal(table.columns().get(1).property(),"userName");
        yes(table.columns().getFirst().primaryKey());yes(table.columns().getFirst().autoIncrement());
        equal(NativeBridge.logsToSql("Preparing: select ?, ?\nParameters: 1(Long), O'Reilly, 张三(String)"),"select 1, 'O''Reilly, 张三';");
        fails(()->NativeBridge.logsToSql("Preparing: select ?, ?\nParameters: 1(Long)"));
        fails(()->NativeBridge.parseDdl("CREATE TABLE x (id bigint"));
        equal(Schema.camel("class",false),"_class");equal(Schema.snake("URLValue"),"url_value");
        var files=CodeGenerator.generate(table,"example",false,true);equal(files.size(),3);
        String xml=files.get("src/main/resources/mapper/UsersMapper.xml");SqlRenderer.parseXml(xml);
        yes(xml.contains("WHERE `id` = #{id}"));yes(xml.contains("keyProperty=\"id\""));
        yes(!xml.contains("VALUES (#{id}"));
        var insert=SqlRenderer.render(xml,"insert","{\"userName\":\"小明\",\"age\":20,\"createdAt\":null}",List.of());
        equal(insert.parameters().get(0),"小明");equal(insert.parameters().get(1),new BigDecimal(20));equal(insert.parameters().get(2),null);
        var plus=CodeGenerator.generate(table,"example",true,true);yes(plus.get("src/main/java/example/mapper/UsersMapper.java").contains("extends BaseMapper<Users>"));
        yes(plus.get("src/main/java/example/model/Users.java").contains("@TableId(value = \"id\", type = IdType.AUTO)"));
        fails(()->CodeGenerator.generate(table,"bad package",false,true));
        var composite=NativeBridge.parseDdl("create table links (a bigint, b bigint, value varchar(20), primary key(a,b))");
        String api=CodeGenerator.generate(composite,"example",false,false).get("src/main/java/example/mapper/LinksMapper.java");
        yes(api.contains("selectById(@Param(\"a\") Long a, @Param(\"b\") Long b)"));fails(()->CodeGenerator.generate(composite,"example",true,true));
        var noKey=CodeGenerator.generate(NativeBridge.parseDdl("create table audit (message varchar(100))"),"example",false,true);
        yes(!noKey.get("src/main/resources/mapper/AuditMapper.xml").contains("<delete"));
    }
    private static void queries()throws Exception{
        var bindings=Map.of("name","name","age","age","ids","ids");
        String query=MethodQuery.generate("findByNameAndAgeGreaterThanOrderByIdDesc","users","example.User",bindings,true);
        yes(query.contains("`name` = #{name} AND `age` &gt; #{age}"));yes(query.contains("ORDER BY `id` DESC"));
        SqlRenderer.parseXml("<mapper namespace=\"example.Mapper\">"+query+"</mapper>");
        yes(MethodQuery.generate("countByName","users",null,bindings,true).contains("COUNT(*)"));
        yes(MethodQuery.generate("existsByName","users",null,bindings,true).contains("resultType=\"boolean\""));
        yes(MethodQuery.generate("findByAgeIsNull","users","example.User",Map.of(),false).contains("IS NULL"));
        fails(()->MethodQuery.generate("findByMissing","users","example.User",bindings,true));
        fails(()->MethodQuery.generate("random","users","example.User",bindings,true));
        fails(()->MethodQuery.generate("findByNameOrderById","users","example.User",bindings,true));
    }
    private static void dynamic()throws Exception{
        String xml="""
            <mapper namespace="example.Mapper">
              <sql id="cols">id, name</sql>
              <select id="search" resultType="unavailable.ProjectClass">
                SELECT <include refid="cols"/> FROM users
                <where>
                  <if test="name != null and name != ''">AND name = #{name}</if>
                  <if test="ids != null and ids.size() > 0">AND id IN
                    <foreach collection="ids" item="item" open="(" close=")" separator=",">#{item}</foreach>
                  </if>
                </where>
              </select>
              <select id="choose"><choose><when test="id != null">SELECT #{id}</when><otherwise>SELECT 0</otherwise></choose></select>
              <update id="update">UPDATE users <set><if test="name != null">name = #{name},</if><if test="age != null">age = #{age},</if></set> WHERE id=#{id}</update>
              <select id="bind"><bind name="pattern" value="'%' + name + '%'"/>SELECT * FROM users WHERE name LIKE #{pattern}</select>
            </mapper>
            """;
        var r=SqlRenderer.render(xml,"search","{\"name\":\"张三\",\"ids\":[1,2,3]}",List.of());
        equal(r.parameters(),List.of("张三",new BigDecimal(1),new BigDecimal(2),new BigDecimal(3)));
        yes(r.sql().contains("WHERE"));yes(r.sql().contains("id, name"));
        var empty=SqlRenderer.render(xml,"search","{}",List.of());yes(!empty.sql().contains("WHERE"));equal(empty.parameters().size(),0);
        equal(SqlRenderer.render(xml,"choose","{}",List.of()).sql(),"SELECT 0");
        equal(SqlRenderer.render(xml,"choose","{\"id\":8}",List.of()).parameters(),List.of(new BigDecimal(8)));
        String update=SqlRenderer.render(xml,"update","{\"id\":1,\"name\":\"Alice\"}",List.of()).sql().replaceAll("\\s+"," ");
        equal(update,"UPDATE users SET name = ? WHERE id=?");
        equal(SqlRenderer.render(xml,"bind","{\"name\":\"Alice\"}",List.of()).parameters(),List.of("%Alice%"));
        String related="<mapper namespace=\"other.Mapper\"><sql id=\"shared\">id, amount</sql></mapper>";
        equal(SqlRenderer.render("<mapper namespace=\"example.Mapper\"><select id=\"external\">SELECT <include refid=\"other.Mapper.shared\"/> FROM users</select></mapper>","external","{}",List.of(related)).sql(),"SELECT  id, amount  FROM users");
        fails(()->SqlRenderer.render(xml,"missing","{}",List.of()));fails(()->SqlRenderer.render(xml,"search","[]",List.of()));
        equal(SqlRenderer.render("<sqlMap namespace='old'><select id='q'><isNotNull property='name'>SELECT #name#</isNotNull></select></sqlMap>","q","{}",List.of()).sql(),"");
        fails(()->SqlRenderer.render("<mapper namespace='cycle'><sql id='a'><include refid='b'/></sql><sql id='b'><include refid='a'/></sql><select id='q'><include refid='a'/></select></mapper>","q","{}",List.of()));
        var secure=SqlRenderer.parseXml("<!DOCTYPE mapper [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><mapper namespace='x'><select id='a'>&secret;</select></mapper>");
        yes(!secure.getDocumentElement().getTextContent().contains("root:"));
    }
    private static void index(){
        equal(NamespaceScanner.extract("<?xml version='1.0'?><mapper namespace='example.UserMapper'><select id='x'>select 1</select></mapper>").keySet(),Set.of("example.UserMapper"));
        equal(NamespaceScanner.extract("<configuration><mapper namespace='wrong'/></configuration>").size(),0);
        equal(NamespaceScanner.extract("<!-- <mapper namespace='fake'> --><mapper namespace=\"real.Mapper\"/>").keySet(),Set.of("real.Mapper"));
        equal(NamespaceScanner.extract("<mapper\n namespace = 'example.MultiLine' />").keySet(),Set.of("example.MultiLine"));
        var legacy=NamespaceScanner.extract("<sqlMap namespace='legacy.Dao'><select id='find'>SELECT #id#</select><procedure id='legacy.Dao.call'>CALL x()</procedure></sqlMap>");
        yes(legacy.containsKey("legacy.Dao"));yes(legacy.containsKey(NamespaceScanner.LEGACY_NAMESPACE+"legacy.Dao"));yes(legacy.containsKey(NamespaceScanner.STATEMENT+"find"));yes(legacy.containsKey(NamespaceScanner.STATEMENT+"legacy.Dao.find"));yes(legacy.containsKey(NamespaceScanner.STATEMENT+"call"));
        yes(NamespaceScanner.extract("<sqlMap><statement id='lookup'>SELECT 1</statement></sqlMap>").containsKey(NamespaceScanner.STATEMENT+"lookup"));
    }
    private static void jniBenchmark(){
        String input="Preparing: SELECT * FROM users WHERE id=? AND name=?\nParameters: 42(Long), O'Reilly(String)";
        for(int i=0;i<2000;i++)NativeBridge.logsToSql(input);
        long start=System.nanoTime();int runs=20000;
        for(int i=0;i<runs;i++)NativeBridge.logsToSql(input);
        System.out.printf(Locale.ROOT,"JNI+JSON logs: %.3f us/op (%d warmed calls)%n",(System.nanoTime()-start)/1000.0/runs,runs);
    }
}
