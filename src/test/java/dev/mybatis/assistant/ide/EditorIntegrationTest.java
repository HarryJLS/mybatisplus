package dev.mybatis.assistant.ide;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;

public final class EditorIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected void setUp()throws Exception{
        super.setUp();
        myFixture.addClass("package java.lang; public final class String { public int length(){return 0;} public boolean isEmpty(){return false;} }");
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}");
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Param { String value(); }");
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Select { String[] value(); }");
        myFixture.addClass("package org.apache.ibatis.type; public @interface Alias { String value(); }");
        myFixture.addClass("package sample; @org.apache.ibatis.type.Alias(\"Person\") public class User { public Long id; public String name; }");
        myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface UserMapper { User find(@org.apache.ibatis.annotations.Param(\"user\") User input); }");
    }
    @Override protected void tearDown()throws Exception{
        try{var s=AssistantSettings.get(getProject());s.typeAliasBindings="";s.typeAliasPackages="";s.consoleSql=false;getProject().getService(AssistantSettings.class).changed();}
        finally{super.tearDown();}
    }
    private XmlFile xml(String body){return (XmlFile)myFixture.addFileToProject("mapper/UserMapper.xml","<mapper namespace='sample.UserMapper'>"+body+"</mapper>");}
    private PsiClass mapper(){return JavaPsiFacade.getInstance(getProject()).findClass("sample.UserMapper",GlobalSearchScope.allScope(getProject()));}
    private boolean warning(String needle){return myFixture.doHighlighting().stream().anyMatch(w->w.getDescription()!=null && w.getDescription().contains(needle));}
    public void testXmlTypeAlias(){
        myFixture.addFileToProject("mybatis-config.xml","<configuration><typeAliases><typeAlias alias='Person' type='sample.User'/></typeAliases></configuration>");
        XmlFile file=xml("<resultMap id='r' type='PERSON'><result column='id' property='id'/></resultMap>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals("sample.User",MapperModel.typeClass(file.getRootTag().findFirstSubTag("resultMap"),"PERSON").getQualifiedName());
    }
    public void testXmlDeclarationValuesAreNotMapperAttributes(){
        XmlFile file=(XmlFile)myFixture.configureByText(XmlFileType.INSTANCE,"<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\"><mapper namespace='sample.UserMapper'><select id='find'>SELECT #{user.name}</select></mapper>");
        for(XmlAttributeValue value:PsiTreeUtil.findChildrenOfType(file,XmlAttributeValue.class))value.getReferences();
        myFixture.enableInspections(new MapperInspection());myFixture.doHighlighting();
    }
    public void testCompletionInsideXmlDeclarationDoesNotCastParent(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<?xml version=\"1.0\" encoding=\"UT<caret>\"?><mapper namespace='sample.UserMapper'><select id='find'>SELECT 1</select></mapper>");
        myFixture.completeBasic();
    }
    public void testPackageAliasAndInvalidation(){
        PsiFile configuration=myFixture.addFileToProject("application.properties","mybatis.type-aliases-package=sample");
        XmlFile file=xml("<resultMap id='r' type='person'/>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals("sample.User",TypeAliases.resolve(file,"person"));
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),()->{PsiDocumentManager.getInstance(getProject()).getDocument(configuration).setText("mybatis.type-aliases-package=missing");});
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertNull(TypeAliases.resolve(file,"person"));
    }
    public void testAliasAmbiguity(){
        myFixture.addClass("package other; public class User {}");
        myFixture.addFileToProject("one.xml","<configuration><typeAliases><typeAlias alias='same' type='sample.User'/></typeAliases></configuration>");
        myFixture.addFileToProject("two.xml","<configuration><typeAliases><typeAlias alias='same' type='other.User'/></typeAliases></configuration>");
        XmlFile file=xml("<resultMap id='r' type='same'/>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());assertNull(TypeAliases.resolve(file,"same"));
    }
    public void testProjectionIncludes(){
        XmlFile file=xml("<sql id='columns'>id, name AS display_name</sql><select id='find'>SELECT <include refid='columns'/> FROM users <where><if test='user != null'>id=#{user.id}</if></where></select>");
        var result=StatementSql.projection(file.getRootTag().findFirstSubTag("select"));assertTrue(result.complete());assertEquals(List.of("id","display_name"),result.columns());
    }
    public void testColumnInspection(){
        myFixture.enableInspections(new MapperInspection());
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><resultMap id='r' type='sample.User'><id column='id' property='id'/><result column='wrong' property='name'/><result column='id' property='name'/></resultMap><select id='find' resultMap='r'>SELECT id, name FROM users</select></mapper>");
        assertTrue(warning("SELECT 输出中找不到 resultMap 列: wrong"));assertTrue(warning("resultMap 重复列: id"));
    }
    public void testWildcardDoesNotInventMissingColumns(){
        myFixture.enableInspections(new MapperInspection());
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><resultMap id='r' type='sample.User'><result column='name' property='name'/></resultMap><select id='find' resultMap='r'>SELECT * FROM users</select></mapper>");
        assertFalse(warning("SELECT 输出中找不到"));
    }
    public void testOgnlChecks(){
        myFixture.enableInspections(new MapperInspection());
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><select id='find'><if test='user.missing != null'>SELECT 1</if><if test='user.name.badMethod()'>SELECT 2</if><if test='user.name !='>SELECT 3</if></select></mapper>");
        assertTrue(warning("找不到 OGNL 参数或属性: user.missing"));assertTrue(warning("找不到 OGNL 方法"));assertTrue(warning("OGNL 语法错误"));
    }
    public void testValidOgnlMethodAndQuotedStrings(){
        myFixture.enableInspections(new MapperInspection());
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><select id='find'><if test='user.name != null and user.name.length() > 0 and user.name == &quot;missing&quot;'>SELECT 1</if></select></mapper>");
        assertFalse(warning("找不到 OGNL"));assertFalse(warning("OGNL 语法错误"));
    }
    public void testCharacterComparedToString(){
        myFixture.enableInspections(new MapperInspection());myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><select id='find'><if test=\"user.name == 'x'\">SELECT 1</if></select></mapper>");assertTrue(warning("单字符单引号"));
    }
    public void testAnnotationReferenceAndInspection(){
        PsiClass cls=myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface AnnotatedMapper { @org.apache.ibatis.annotations.Select(\"SELECT #{user.name}, #{user.missing}\") User find(@org.apache.ibatis.annotations.Param(\"user\") User u); }");
        PsiLiteralExpression literal=PsiTreeUtil.findChildrenOfType(cls,PsiLiteralExpression.class).stream().filter(l->AnnotationSql.method(l)!=null).findFirst().orElseThrow();
        assertTrue(Arrays.stream(AnnotationSql.references(literal)).anyMatch(r->r.resolve() instanceof PsiField field && field.getName().equals("name")));
        myFixture.enableInspections(new AnnotationInspection());myFixture.configureFromExistingVirtualFile(cls.getContainingFile().getVirtualFile());assertTrue(warning("找不到注解 SQL 参数或属性: user.missing"));
    }
    public void testAnnotationArrayAndScriptPreview()throws Exception{
        PsiClass cls=myFixture.addClass("package sample; public interface ScriptMapper { @org.apache.ibatis.annotations.Select({\"<script>SELECT id FROM users\", \"<where><if test='id != null'>id=#{id}</if></where></script>\"}) User find(@org.apache.ibatis.annotations.Param(\"id\") Long id); }");
        PsiMethod method=cls.getMethods()[0];var rendered=dev.mybatis.assistant.core.SqlRenderer.render(AnnotationSql.mapperXml(method),"find","{\"id\":7}",List.of());assertTrue(rendered.sql().contains("WHERE"));assertEquals(List.of(new java.math.BigDecimal(7)),rendered.parameters());
    }
    public void testParamRenameInXmlAndAnnotation(){
        PsiClass cls=myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface RenameMapper { @org.apache.ibatis.annotations.Select(\"SELECT #{value.name}\") User find(@org.apache.ibatis.annotations.Param(\"value\") User javaName); }");
        PsiFile file=myFixture.addFileToProject("mapper/RenameMapper.xml","<mapper namespace='sample.RenameMapper'><select id='find'><if test='value != null'>SELECT #{value.name}</if></select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        PsiParameter parameter=cls.getMethods()[0].getParameterList().getParameters()[0];ParamRenameHandler.rename(parameter,"criteria");
        assertEquals("javaName",parameter.getName());assertEquals("criteria",ParameterModel.paramName(parameter));assertTrue(file.getText(),file.getText().contains("criteria != null"));assertTrue(file.getText().contains("#{criteria.name}"));assertTrue(cls.getText(),cls.getText().contains("#{criteria.name}"));
    }
    public void testConsolePairsThreadAndIgnoresAmbiguity(){
        AssistantSettings.get(getProject()).consoleSql=true;var filter=new SqlConsoleFilterProvider.SqlFilter(getProject());
        assertNull(filter.applyFilter("[one] Preparing: SELECT ?\n",30));assertNull(filter.applyFilter("[two] Preparing: SELECT ?\n",60));
        assertNotNull(filter.applyFilter("[one] Parameters: 1(Long)\n",90));assertNotNull(filter.applyFilter("[two] Parameters: 2(Long)\n",120));
        filter.applyFilter("Preparing: SELECT ?\n",150);filter.applyFilter("Preparing: SELECT ?\n",180);assertNull(filter.applyFilter("Parameters: 1(Long)\n",210));
    }
    public void testMappingIdRenameCrossNamespace(){
        XmlFile first=(XmlFile)myFixture.addFileToProject("mapper/First.xml","<mapper namespace='sample.First'><sql id='cols'>id,name</sql><resultMap id='r' type='sample.User'/></mapper>");
        PsiFile second=myFixture.addFileToProject("mapper/Second.xml","<mapper namespace='sample.Second'><select id='find' resultMap='sample.First.r'>SELECT <include refid='sample.First.cols'/> FROM users</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        MappingIdRenameHandler.rename(first.getRootTag().findFirstSubTag("sql").getAttribute("id").getValueElement(),"columns");
        MappingIdRenameHandler.rename(first.getRootTag().findFirstSubTag("resultMap").getAttribute("id").getValueElement(),"mapped");
        assertTrue(second.getText(),second.getText().contains("sample.First.columns"));assertTrue(second.getText().contains("sample.First.mapped"));
    }
    public void testInheritedMapperGenericParameter(){
        myFixture.addClass("package sample; public interface BaseMapper<T> { T search(@org.apache.ibatis.annotations.Param(\"entity\") T value); }");
        myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface ChildMapper extends BaseMapper<User> {}");
        XmlFile file=(XmlFile)myFixture.addFileToProject("mapper/Child.xml","<mapper namespace='sample.ChildMapper'><select id='search'>SELECT #{entity.name}</select></mapper>");
        XmlTag statement=file.getRootTag().findFirstSubTag("select");var model=ParameterModel.of(MapperModel.method(statement),statement);assertNotNull(model.resolve("entity.name"));
    }
    public void testForeachRenamePreservesShadowedVariableAndIndex(){
        myFixture.addClass("package sample; public interface ListMapper { User find(@org.apache.ibatis.annotations.Param(\"users\") java.util.List<User> users); }");
        XmlFile file=(XmlFile)myFixture.addFileToProject("mapper/List.xml","<mapper namespace='sample.ListMapper'><select id='find'><foreach collection='users' item='u' index='i'>#{u.name},#{i}<foreach collection='users' item='u'>#{u.name}</foreach></foreach></select></mapper>");
        XmlTag loop=file.getRootTag().findFirstSubTag("select").findFirstSubTag("foreach");ScopedVariableRenameHandler.rename(loop.getAttribute("item").getValueElement(),"user");
        assertTrue(file.getText(),file.getText().contains("#{user.name},#{i}"));assertEquals("u",loop.findFirstSubTag("foreach").getAttributeValue("item"));assertTrue(loop.findFirstSubTag("foreach").getText().contains("#{u.name}"));
    }
    public void testBindRename(){
        XmlFile file=xml("<select id='find'><bind name='pattern' value='user.name'/><if test='pattern != null'>SELECT #{pattern}</if></select>");
        XmlTag bind=file.getRootTag().findFirstSubTag("select").findFirstSubTag("bind");ScopedVariableRenameHandler.rename(bind.getAttribute("name").getValueElement(),"searchPattern");assertTrue(file.getText(),file.getText().contains("searchPattern != null"));assertTrue(file.getText().contains("#{searchPattern}"));
    }
    public void testParamRenamePreservesEncodedStringLiteral(){
        XmlFile file=xml("<select id='find'><if test='user != null and user.name == &quot;user&quot; and user.name.length() > 0'>SELECT #{user.name}</if></select>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        ParamRenameHandler.rename(mapper().getMethods()[0].getParameterList().getParameters()[0],"criteria");
        assertTrue(file.getText(),file.getText().contains("criteria != null"));assertTrue(file.getText().contains("criteria.name.length()"));assertTrue(file.getText().contains("&quot;user&quot;"));
    }
    public void testColumnPrefixInspection(){
        myFixture.enableInspections(new MapperInspection());
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><resultMap id='r' type='sample.User'><association property='name' javaType='sample.User' columnPrefix='u_'><id column='id' property='id'/><result column='missing' property='name'/></association></resultMap><select id='find' resultMap='r'>SELECT id AS u_id, name AS u_name FROM users</select></mapper>");
        assertFalse(warning("找不到 resultMap 列: u_id"));assertTrue(warning("找不到 resultMap 列: u_missing"));
    }
    public void testInjectionRangesPreserveJavaEscapesAndBinding(){
        PsiClass cls=myFixture.addClass("package sample; public interface InjectionMapper { @org.apache.ibatis.annotations.Select(\"SELECT \\\"name\\\" FROM users WHERE id=#{id}\") User find(@org.apache.ibatis.annotations.Param(\"id\") Long id); }");
        PsiLiteralExpression literal=PsiTreeUtil.findChildrenOfType(cls,PsiLiteralExpression.class).stream().filter(l->AnnotationSql.method(l)!=null).findFirst().orElseThrow();
        List<SqlLanguageInjector.Place> places=SqlLanguageInjector.places((PsiLanguageInjectionHost)literal);assertEquals(2,places.size());assertEquals("?",places.get(1).prefix());assertFalse(places.get(0).range().substring(literal.getText()).contains("#{"));
    }
    public void testInjectionSkipsDollarIdentifiers(){
        PsiClass cls=myFixture.addClass("package sample; public interface DollarMapper { @org.apache.ibatis.annotations.Select(\"SELECT * FROM ${table}\") User find(String table); }");
        PsiLiteralExpression literal=PsiTreeUtil.findChildrenOfType(cls,PsiLiteralExpression.class).stream().filter(l->AnnotationSql.method(l)!=null).findFirst().orElseThrow();assertTrue(SqlLanguageInjector.places((PsiLanguageInjectionHost)literal).isEmpty());
    }
    public void testImplicitXmlAliasUsesAnnotation(){
        myFixture.addFileToProject("config.xml","<configuration><typeAliases><typeAlias type='sample.User'/></typeAliases></configuration>");
        XmlFile file=xml("<resultMap id='r' type='person'/>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());assertEquals("sample.User",TypeAliases.resolve(file,"person"));assertNull(TypeAliases.resolve(file,"user"));
    }
    public void testCollectionPseudoPropertiesAndArrayLength(){
        myFixture.addClass("package sample; public interface CollectionMapper { User find(@org.apache.ibatis.annotations.Param(\"users\") java.util.List<User> users,@org.apache.ibatis.annotations.Param(\"array\") User[] array); }");
        XmlFile file=(XmlFile)myFixture.addFileToProject("mapper/Collection.xml","<mapper namespace='sample.CollectionMapper'><select id='find'>SELECT 1</select></mapper>");XmlTag statement=file.getRootTag().findFirstSubTag("select");var model=ParameterModel.of(MapperModel.method(statement),statement);
        assertNotNull(model.resolve("users.size"));assertNotNull(model.resolve("users.isEmpty"));assertNotNull(model.resolve("array.length"));
    }
    public void testScriptArrayDoesNotRenameLocalAsGlobalParameter(){
        PsiClass cls=myFixture.addClass("package sample; public interface ScriptBindingMapper { @org.apache.ibatis.annotations.Select({\"<script>SELECT\",\"<foreach collection='ids' item='value'>#{value}</foreach></script>\"}) User find(@org.apache.ibatis.annotations.Param(\"value\") Long input,@org.apache.ibatis.annotations.Param(\"ids\") java.util.List<Long> ids); }");
        PsiMethod method=cls.getMethods()[0];
        for(PsiLiteralExpression literal:PsiTreeUtil.findChildrenOfType(method,PsiLiteralExpression.class))if(AnnotationSql.method(literal)!=null)assertEquals(0,AnnotationSql.references(literal).length);
        try{ParamRenameHandler.rename(method.getParameterList().getParameters()[0],"criteria");fail("script binding rename must be rejected");}catch(IllegalArgumentException expected){assertEquals("value",ParameterModel.paramName(method.getParameterList().getParameters()[0]));}
    }
    public void testExternalAnnotationConstantRejectsPartialBindingRename(){
        myFixture.addClass("package sample; public class SqlConstants { public static final String FIND=\"SELECT #{value}\"; }");
        PsiClass cls=myFixture.addClass("package sample; public interface ConstantMapper { @org.apache.ibatis.annotations.Select(SqlConstants.FIND) User find(@org.apache.ibatis.annotations.Param(\"value\") Long input); }");
        PsiMethod method=cls.getMethods()[0];assertEquals("SELECT #{value}",AnnotationSql.sql(method));
        try{ParamRenameHandler.rename(method.getParameterList().getParameters()[0],"criteria");fail("external constant must not be left with old binding");}catch(IllegalArgumentException expected){assertEquals("value",ParameterModel.paramName(method.getParameterList().getParameters()[0]));}
    }
}
