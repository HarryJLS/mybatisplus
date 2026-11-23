package dev.mybatis.assistant.core;

import java.util.*;
import java.math.BigDecimal;

/** Standalone MyBatis tests: a concrete SQL/binding assertion and an opt-in rollback test. */
public final class MapperTestGenerator {
    private MapperTestGenerator() {}
    public record Files(String javaPath,String javaSource,String configPath,String configSource) {}
    public static Files generate(String namespace,String method,String kind,List<String> resources,Map<String,String> aliases,Object parameters,SqlRenderer.Result expected,boolean junit4){
        if(!namespace.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*") || !method.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*") || !Set.of("select","insert","update","delete").contains(kind))throw new IllegalArgumentException("测试需要有效的 Mapper namespace、方法名和语句类型");
        if(resources.isEmpty() || resources.size()>64 || expected.sql().length()>262144 || !expected.notes().isEmpty())throw new IllegalArgumentException("测试需要完整的 Mapper 资源及无跳过项的 SQL 渲染结果");
        int dot=namespace.lastIndexOf('.');String pkg=dot<0?"":namespace.substring(0,dot),name=namespace.substring(dot+1)+"_"+method+"SqlTest",config="mybatis-assistant-tests/"+(namespace+"_"+method).replace('.','/')+".xml";
        String imports=junit4?"import org.junit.Test;\nimport static org.junit.Assert.*;\n":"import org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\n";
        String assumption=junit4?"org.junit.Assume.assumeTrue(\"Enable with -Dmybatis.test.database=true\", Boolean.getBoolean(\"mybatis.test.database\"));":"org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean(\"mybatis.test.database\"), \"Enable with -Dmybatis.test.database=true\");";
        String operation=kind.equals("select")?"List<?> result = session.selectList(STATEMENT, parameters);\n            assertNotNull(result);":"int affected = session."+kind+"(STATEMENT, parameters);\n            assertTrue(affected >= 0);";
        String source=(pkg.isEmpty()?"":"package "+pkg+";\n\n")+imports+"""
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

public class %s {
    private static final String STATEMENT = %s;

    private SqlSessionFactory factory() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(%s)) {
            assertNotNull(input);
            return new SqlSessionFactoryBuilder().build(input);
        }
    }

    private Object parameters() {
        // Replace these sample inputs and regenerate expected SQL when testing another branch.
        return %s;
    }

    @Test
    public void test%sSql() throws Exception {
        Configuration config = factory().getConfiguration();
        BoundSql sql = config.getMappedStatement(STATEMENT).getBoundSql(parameters());
        assertEquals(%s, sql.getSql().trim().replaceAll("\\\\s+", " "));
        List<Object> bindings = new ArrayList<>();
        for (ParameterMapping mapping : sql.getParameterMappings()) {
            assertEquals(ParameterMode.IN, mapping.getMode());
            Object value = sql.hasAdditionalParameter(mapping.getProperty())
                ? sql.getAdditionalParameter(mapping.getProperty())
                : config.newMetaObject(sql.getParameterObject()).getValue(mapping.getProperty());
            bindings.add(value);
        }
        assertEquals(%s, bindings);
    }

    @Test
    public void test%sDatabaseRollback() throws Exception {
        %s
        String url = System.getProperty("mybatis.test.jdbc.url");
        String driver = System.getProperty("mybatis.test.jdbc.driver");
        assertNotNull(url);
        assertNotNull(driver);
        SqlSessionFactory factory = factory();
        factory.getConfiguration().setEnvironment(new Environment("test", new JdbcTransactionFactory(),
            new UnpooledDataSource(driver, url, System.getProperty("mybatis.test.jdbc.user", ""),
                System.getProperty("mybatis.test.jdbc.password", ""))));
        Object parameters = parameters();
        try (SqlSession session = factory.openSession(false)) {
            %s
            // TODO: Add business result assertions and valid fixtures for this test database.
            session.rollback(true);
        }
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
""".formatted(name,literal(namespace+"."+method),literal(config),literal(parameters),method,literal(expected.sql().trim().replaceAll("\\s+"," ")),literal(expected.parameters()),method,assumption,operation);
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE configuration PUBLIC \"-//mybatis.org//DTD Config 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-config.dtd\">\n<configuration>\n");
        if(!aliases.isEmpty()){xml.append("  <typeAliases>\n");for(var alias:aliases.entrySet())xml.append("    <typeAlias alias=\"").append(Schema.xml(alias.getKey())).append("\" type=\"").append(Schema.xml(alias.getValue())).append("\"/>\n");xml.append("  </typeAliases>\n");}
        xml.append("  <mappers>\n");for(String resource:new LinkedHashSet<>(resources)){if(resource.contains("..") || resource.startsWith("/") || resource.contains(":") || resource.length()>4096)throw new IllegalArgumentException("Mapper 需要 classpath 资源相对路径");xml.append("    <mapper resource=\"").append(Schema.xml(resource)).append("\"/>\n");}xml.append("  </mappers>\n</configuration>\n");
        if(source.length()>524288)throw new IllegalArgumentException("生成测试超过 512 KiB，请缩小测试参数");
        return new Files("src/test/java/"+(pkg.isEmpty()?"":pkg.replace('.','/')+"/")+name+".java",source,"src/test/resources/"+config,xml.toString());
    }
    public static String literal(Object value){return literal(value,0,new int[]{0});}
    private static String literal(Object value,int depth,int[] count){
        if(depth>32 || ++count[0]>10000)throw new IllegalArgumentException("测试参数展开超过上限");
        if(value==null)return "null";
        if(value instanceof String text){StringBuilder out=new StringBuilder("\"");for(char c:text.toCharArray())switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();}
        if(value instanceof BigDecimal n)return "new BigDecimal("+literal(n.toString(),depth+1,count)+")";
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number)return "new BigDecimal("+literal(value.toString(),depth+1,count)+")";
        if(value instanceof List<?> list)return "Arrays.asList("+String.join(", ",list.stream().map(v->literal(v,depth+1,count)).toList())+")";
        if(value instanceof Map<?,?> map){List<String> entries=new ArrayList<>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("测试 Map 键需要字符串");entries.add(literal(entry.getKey(),depth+1,count));entries.add(literal(entry.getValue(),depth+1,count));}return "map("+String.join(", ",entries)+")";}
        throw new IllegalArgumentException("测试参数需要 JSON 值");
    }
}
