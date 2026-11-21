package dev.mybatis.assistant.ide;

import com.google.gson.*;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.psi.*;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import io.netty.handler.codec.http.*;
import java.util.*;

public final class RuntimeIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected void setUp()throws Exception{
        super.setUp();myFixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}");
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Param { String value(); }");
        myFixture.addClass("package org.mybatis.spring.annotation; public @interface MapperScan { String[] value() default {}; String[] basePackages() default {}; Class<?>[] basePackageClasses() default {}; Class<?> annotationClass() default java.lang.annotation.Annotation.class; Class<?> markerInterface() default Class.class; }");
        myFixture.addClass("package org.mybatis.spring.annotation; public @interface MapperScans { MapperScan[] value(); }");
        myFixture.addClass("package sample; public class User { public Long id; }");
        myFixture.addClass("package sample.mapper; @org.apache.ibatis.annotations.Mapper public interface UserMapper { sample.User find(@org.apache.ibatis.annotations.Param(\"id\") Long id); }");
    }
    @Override protected void tearDown()throws Exception{
        try{var s=AssistantSettings.get(getProject());s.mcpEnabled=false;s.mcpDatabaseRead=false;s.mcpDmlRollback=false;s.jdbcUrl="";s.springIntegration=true;getProject().getService(AssistantSettings.class).changed();}finally{super.tearDown();}
    }
    public void testMapperScanExplicitAndMarkerFilter(){
        myFixture.addClass("package sample.mapper; public interface Other {}");
        PsiClass config=myFixture.addClass("package app; @org.mybatis.spring.annotation.MapperScan(basePackages=\"sample.mapper\",annotationClass=org.apache.ibatis.annotations.Mapper.class) public class Config {}");
        assertEquals(List.of("sample.mapper"),SpringMapperModel.config(config).rules().getFirst().packages());
        assertEquals(List.of("sample.mapper.UserMapper"),SpringMapperModel.mappers(config).stream().map(PsiClass::getQualifiedName).toList());
    }
    public void testMapperScanClassPackageAndCacheInvalidation(){
        PsiClass config=myFixture.addClass("package app; @org.mybatis.spring.annotation.MapperScan(basePackageClasses=sample.mapper.UserMapper.class) public class Config {}");
        assertEquals(1,SpringMapperModel.mappers(config).size());myFixture.addClass("package sample.mapper; public interface SecondMapper {}");assertEquals(2,SpringMapperModel.mappers(config).size());
    }
    public void testDynamicScanDoesNotSuppressOtherLiteralScan(){
        PsiClass config=myFixture.addClass("package app; @org.mybatis.spring.annotation.MapperScans({@org.mybatis.spring.annotation.MapperScan(\"${mappers}\"),@org.mybatis.spring.annotation.MapperScan(\"sample.mapper\")}) public class Config {}");
        assertTrue(SpringMapperModel.config(config).skippedDynamic());assertEquals(1,SpringMapperModel.config(config).rules().size());assertEquals(1,SpringMapperModel.mappers(config).size());
    }
    public void testMapperScanMissingPackageInspection(){
        myFixture.enableInspections(new MapperScanInspection());myFixture.configureByText("Config.java","package app; @org.mybatis.spring.annotation.MapperScan(\"missing.mappers\") public class Config {}");assertTrue(myFixture.doHighlighting().stream().anyMatch(h->h.getDescription()!=null && h.getDescription().contains("MapperScan 包不存在")));
    }
    public void testMcpDisabledTokenAndProjectIsolation(){
        var service=getProject().getService(McpProjectService.class);String token=JsonParser.parseString(service.clientConfig(63342)).getAsJsonObject().getAsJsonObject("mcpServers").getAsJsonObject("mybatis-assistant").getAsJsonObject("headers").get("Authorization").getAsString().substring(7);
        assertFalse(service.accepts(token));AssistantSettings.get(getProject()).mcpEnabled=true;assertTrue(service.accepts(token));assertFalse(service.accepts("wrong"));var second=new McpProjectService(getProject());assertFalse(second.accepts(token));second.dispose();
        assertTrue(service.acquire());assertTrue(service.acquire());assertTrue(service.acquire());assertTrue(service.acquire());assertFalse(service.acquire());for(int i=0;i<4;i++)service.release();
    }
    public void testMcpReadsIndexedMapperAndRejectsDatabaseWithoutPermission()throws Exception{
        AssistantSettings.get(getProject()).mcpEnabled=true;myFixture.addFileToProject("mapper/User.xml","<mapper namespace='sample.mapper.UserMapper'><select id='find'>SELECT #{id}</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var service=getProject().getService(McpProjectService.class);JsonObject query=new JsonObject();query.addProperty("namespace","sample.mapper.UserMapper");assertEquals(1,service.call("find_mapper_xml",query).getAsJsonArray("files").size());assertEquals("sample.mapper.UserMapper",service.call("find_mapper_interface",query).getAsJsonObject("mapper").get("qualifiedName").getAsString());
        JsonObject database=new JsonObject();database.addProperty("dataSource","manual");try{service.call("list_tables",database);fail("database permissions required");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("数据库访问"));}
    }
    public void testMcpHttpRejectsOriginAndDnsRebinding(){
        DefaultHttpRequest request=new DefaultHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,McpHttpHandler.PATH);request.headers().set(HttpHeaderNames.HOST,"127.0.0.1:63342");assertTrue(McpHttpHandler.localHeaders(request));request.headers().set(HttpHeaderNames.ORIGIN,"https://evil.example");assertFalse(McpHttpHandler.localHeaders(request));request.headers().remove(HttpHeaderNames.ORIGIN);request.headers().set(HttpHeaderNames.HOST,"localhost.evil.example:63342");assertFalse(McpHttpHandler.localHeaders(request));
    }
    public void testDiagnosticsReportsActualScope(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.mapper.UserMapper'><select id='find'>SELECT #{missing}</select></mapper>");var report=MapperDiagnostics.inspect(myFixture.getFile());assertTrue(report.issues().stream().anyMatch(i->i.message().contains("找不到参数")));assertTrue(report.checksSkipped().contains("数据库表/字段语义"));
    }
    public void testSnapshotPreservesXmlAndParameterInput(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.mapper.UserMapper'><select id='find'>SELECT #{id}</select></mapper>");var snapshot=MapperSnapshot.capture(myFixture.getFile(),"find","{\"id\":1}");assertEquals("find",snapshot.statementId());assertEquals("{\"id\":1}",snapshot.json());assertTrue(snapshot.related().isEmpty());
    }
}
