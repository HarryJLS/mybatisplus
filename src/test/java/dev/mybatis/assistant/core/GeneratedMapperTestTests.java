package dev.mybatis.assistant.core;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import javax.tools.ToolProvider;

/** Compile and run the delivered JUnit source against the real MyBatis runtime. */
public final class GeneratedMapperTestTests {
    public static void main(String[] args)throws Exception{
        String xml="<?xml version='1.0'?><!DOCTYPE mapper PUBLIC '-//mybatis.org//DTD Mapper 3.0//EN' 'https://mybatis.org/dtd/mybatis-3-mapper.dtd'><mapper namespace='generated.SampleMapper'><select id='find' resultType='map'>SELECT id, name FROM users <where><if test='name != null'>AND name=#{name}</if><if test='ids != null and !ids.isEmpty()'>AND id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></if></where></select><update id='change'>UPDATE users SET name=#{name} WHERE id=#{id}</update></mapper>";
        String json="{\"name\":\"O'Reilly\\n\",\"ids\":[1,2]}";var result=SqlRenderer.render(xml,"find",json,List.of());
        Path root=Files.createTempDirectory("mapper-generated-test");
        String h2=System.getProperty("mybatis.test.h2");
        try(URLClassLoader runtime=new URLClassLoader(h2==null?new java.net.URL[0]:new java.net.URL[]{Path.of(h2).toUri().toURL()},GeneratedMapperTestTests.class.getClassLoader())){
            for(boolean junit4:List.of(false,true))for(String method:List.of("find","change")){
                Path folder=root.resolve((junit4?"junit4":"junit5")+method);Files.createDirectories(folder);
                String pkg=junit4?"org.junit":"org.junit.jupiter.api",assertions=junit4?"Assert":"Assertions";
                Map<String,String> stubs=new LinkedHashMap<>();stubs.put(pkg.replace('.','/')+"/Test.java","package "+pkg+"; public @interface Test {}");
                stubs.put(pkg.replace('.','/')+"/"+assertions+".java","package "+pkg+"; public class "+assertions+" { public static void assertEquals(Object a,Object b){if(!java.util.Objects.equals(a,b))throw new AssertionError(a+\" != \"+b);} public static void assertTrue(boolean x){if(!x)throw new AssertionError();} public static void assertNotNull(Object x){if(x==null)throw new AssertionError();} }");
                stubs.put(pkg.replace('.','/')+"/"+(junit4?"Assume":"Assumptions")+".java","package "+pkg+"; public class "+(junit4?"Assume":"Assumptions")+" { public static void assumeTrue("+(junit4?"String reason,boolean x":"boolean x,String reason")+"){if(!x)throw new IllegalStateException(\"skipped\");} }");
                String parameters=method.equals("find")?json:"{\"id\":1,\"name\":\"changed\"}";
                var rendered=method.equals("find")?result:SqlRenderer.render(xml,method,parameters,List.of());
                var generated=MapperTestGenerator.generate("generated.SampleMapper",method,method.equals("find")?"select":"update",List.of("mapper/Sample.xml"),Map.of(),SqlRenderer.jsonValue(parameters),rendered,junit4);
                List<String> compilerArgs=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",folder.toString()));
                for(var entry:stubs.entrySet()){Path file=folder.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());compilerArgs.add(file.toString());}
                Path source=folder.resolve("SampleMapper_"+method+"SqlTest.java");Files.writeString(source,generated.javaSource());compilerArgs.add(source.toString());
                Path mapper=folder.resolve("mapper/Sample.xml");Files.createDirectories(mapper.getParent());Files.writeString(mapper,xml);Path config=folder.resolve(generated.configPath().substring("src/test/resources/".length()));Files.createDirectories(config.getParent());Files.writeString(config,generated.configSource());
                if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compilerArgs.toArray(String[]::new))!=0)throw new AssertionError("generated test did not compile");
                try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{folder.toUri().toURL()},runtime)){ClassLoader previous=Thread.currentThread().getContextClassLoader();try{Thread.currentThread().setContextClassLoader(loader);Class<?> test=loader.loadClass("generated.SampleMapper_"+method+"SqlTest");Object instance=test.getConstructor().newInstance();test.getMethod("test"+method+"Sql").invoke(instance);if(h2!=null)database(loader,test,instance,method,parameters);}finally{Thread.currentThread().setContextClassLoader(previous);}}
            }
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("PASS: 4 generated JUnit 4/5 SELECT/UPDATE tests compiled and SQL/bindings passed with real MyBatis"+(System.getProperty("mybatis.test.h2")==null?"; no database used":"; H2 query and update rollback passed"));
    }
    private static void database(ClassLoader loader,Class<?> test,Object instance,String method,String parameters)throws Exception{
        java.sql.Driver driver=(java.sql.Driver)loader.loadClass("org.h2.Driver").getConstructor().newInstance();String url="jdbc:h2:mem:generated_"+UUID.randomUUID();
        Map<String,String> previous=new HashMap<>();for(String key:List.of("mybatis.test.database","mybatis.test.jdbc.url","mybatis.test.jdbc.driver","mybatis.test.jdbc.user","mybatis.test.jdbc.password"))previous.put(key,System.getProperty(key));
        try(var connection=driver.connect(url,new Properties())){
            try(var statement=connection.createStatement()){statement.execute("CREATE TABLE users(id INT PRIMARY KEY,name VARCHAR(100))");}
            try(var insert=connection.prepareStatement("INSERT INTO users VALUES(1,?)")){insert.setString(1,method.equals("find")?((Map<?,?>)SqlRenderer.jsonValue(parameters)).get("name").toString():"before");insert.executeUpdate();}
            System.setProperty("mybatis.test.database","true");System.setProperty("mybatis.test.jdbc.url",url);System.setProperty("mybatis.test.jdbc.driver","org.h2.Driver");System.setProperty("mybatis.test.jdbc.user","");System.setProperty("mybatis.test.jdbc.password","");
            test.getMethod("test"+method+"DatabaseRollback").invoke(instance);
            if(method.equals("change"))try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT name FROM users WHERE id=1")){if(!rows.next() || !rows.getString(1).equals("before"))throw new AssertionError("generated update test did not roll back");}
        }finally{previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});}
    }
}
