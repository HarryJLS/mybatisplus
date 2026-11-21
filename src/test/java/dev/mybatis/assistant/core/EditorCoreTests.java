package dev.mybatis.assistant.core;

import java.util.*;

public final class EditorCoreTests {
    private static int checks;
    private static void equal(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    private static void fails(String expression){checks++;try{OgnlSyntax.parse(expression);}catch(Exception e){return;}throw new AssertionError("invalid expression accepted: "+expression);}
    public static void main(String[] args)throws Exception{
        var projection=SqlProjection.parse("SELECT u.id AS user_id, `u`.`name`, concat(first, ',', last) display_name FROM users u");
        equal(projection.columns(),List.of("user_id","name","display_name"));equal(projection.complete(),true);
        equal(SqlProjection.parse("SELECT * FROM users").complete(),false);
        equal(SqlProjection.parse("SELECT u.*, u.id FROM users u").columns(),List.of("id"));
        equal(SqlProjection.parse("SELECT u.*, u.id FROM users u").complete(),false);
        equal(SqlProjection.parse("SELECT sum(amount) FROM orders").complete(),false);
        equal(SqlProjection.parse("SELECT /* FROM misleading */ id, 'x,from' AS label FROM users").columns(),List.of("id","label"));
        equal(SqlProjection.parse("WITH t AS (SELECT id FROM users) SELECT t.id AS selected FROM t").columns(),List.of("selected"));
        equal(SqlProjection.parse("SELECT id, name FROM users WHERE name=#{name} AND age > #{age}").complete(),true);
        equal(SqlProjection.parse("SELECT ? id ? FROM users").complete(),false);
        equal(SqlProjection.parse("SELECT [user id], \"double\"\"quote\" FROM users").columns(),List.of("user id","double\"quote"));
        equal(SqlProjection.parse("UPDATE users SET name=#{name}").complete(),false);
        var config=TypeAliasConfig.parse("<configuration><typeAliases><typeAlias alias='Person' type='sample.User'/><typeAlias type='sample.Address'/><package name='sample.model'/></typeAliases></configuration>","mybatis.xml");
        equal(config.aliases(),Map.of("person","sample.User","#implicit:sample.address","sample.Address"));equal(config.packages(),List.of("sample.model"));
        equal(TypeAliasConfig.parse("mybatis.type-aliases-package=sample.model,sample.other", "application.properties").packages(),List.of("sample.model","sample.other"));
        equal(TypeAliasConfig.parse("mybatis:\n  type-aliases-package: 'sample.model' # comment\nspring:\n  type-aliases-package: wrong", "application.yml").packages(),List.of("sample.model"));
        equal(TypeAliasConfig.parse("mybatis:\n  type-aliases-package: ${MODEL_PACKAGE}", "application.yml").packages(),List.of());
        equal(TypeAliasConfig.parse("<mapper><typeAlias alias='fake' type='sample.User'/></mapper>","mapper.xml").aliases(),Map.of());
        OgnlSyntax.parse("name != null and name.length() > 0");checks++;
        OgnlSyntax.parse("users[0].name == \"Alice\"");checks++;
        OgnlSyntax.parse("@java.lang.Integer@MAX_VALUE > 1");checks++;
        fails("name !=");fails("users[");fails("(".repeat(65)+"name"+")".repeat(65));
        System.out.println("PASS: "+checks+" editor core assertions");
    }
}
