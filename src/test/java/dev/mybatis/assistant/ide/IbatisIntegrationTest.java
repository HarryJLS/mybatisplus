package dev.mybatis.assistant.ide;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;

public final class IbatisIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private boolean previousAutocomplete;
    @Override protected void setUp()throws Exception{
        super.setUp();previousAutocomplete=com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION;com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION=false;
        myFixture.addClass("package legacy.test; public class Person { public Long id; public String name; public java.util.List<Person> children; }");
        myFixture.addClass("package legacy.test; public interface PersonDao { Person find(Long id); Person load(Person query); java.util.List<Person> findMany(java.util.List<Person> people); void helper(); }");
        myFixture.addClass("package com.ibatis.sqlmap.client; public interface SqlMapClient { Object queryForObject(String id, Object parameter); java.util.List queryForList(String id, Object parameter); }");
    }
    @Override protected void tearDown()throws Exception{
        try{com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION=previousAutocomplete;AssistantSettings.get(getProject()).namespaceBindings="";getProject().getService(AssistantSettings.class).changed();}
        finally{super.tearDown();}
    }
    private PsiClass dao(){return JavaPsiFacade.getInstance(getProject()).findClass("legacy.test.PersonDao",GlobalSearchScope.allScope(getProject()));}
    private XmlTag sqlMap(String name,String xml){PsiFile file=myFixture.addFileToProject(name,xml);IndexingTestUtil.waitUntilIndexesAreReady(getProject());return ((XmlFile)file).getRootTag();}
    public void testQualifiedNamespaceBothDirections(){
        XmlTag root=sqlMap("sql/People.xml","<sqlMap namespace='legacy.test.PersonDao'><select id='find' resultClass='legacy.test.Person'>SELECT #id#</select></sqlMap>");
        PsiMethod method=dao().findMethodsByName("find",false)[0];assertEquals(1,MapperModel.statements(method).size());assertEquals(method,MapperModel.method(root.findFirstSubTag("select")));
        assertTrue(Arrays.stream(root.findFirstSubTag("select").getAttribute("id").getValueElement().getReferences()).anyMatch(r->r.resolve() instanceof PsiMethod));
    }
    public void testShortNamespaceQualifiedIdAndClassGutter(){
        XmlTag root=sqlMap("sql/People.xml","<sqlMap namespace='PersonDao'><select id='PersonDao.find'>SELECT #id:INTEGER#</select></sqlMap>");
        PsiMethod method=dao().findMethodsByName("find",false)[0];assertEquals(1,MapperModel.statements(method).size());assertEquals(method,MapperModel.method(root.findFirstSubTag("select")));
        List<com.intellij.codeInsight.daemon.LineMarkerInfo<?>> markers=new ArrayList<>();new MapperGutter().collectSlowLineMarkers(List.of(dao().getNameIdentifier()),markers);assertEquals(1,markers.size());
    }
    public void testNoNamespaceFilenameConvention(){
        XmlTag root=sqlMap("sql/PersonDao.xml","<sqlMap><select id='find'>SELECT #id#</select></sqlMap>");
        assertEquals(1,MapperModel.statements(dao().findMethodsByName("find",false)[0]).size());assertEquals(dao(),MapperModel.mapperClass(root));
    }
    public void testConfiguredNamespaceInvalidatesCache(){
        XmlTag root=sqlMap("sql/People.xml","<sqlMap namespace='legacy.People'><select id='find'>SELECT #id#</select></sqlMap>");PsiMethod method=dao().findMethodsByName("find",false)[0];assertEquals(0,MapperModel.statements(method).size());
        AssistantSettings.get(getProject()).namespaceBindings="legacy.test.PersonDao=legacy.People";getProject().getService(AssistantSettings.class).changed();
        assertEquals(1,MapperModel.statements(method).size());assertEquals(dao(),MapperModel.mapperClass(root));
    }
    public void testAmbiguousShortNamespaceNotGuessed(){
        myFixture.addClass("package another; public interface PersonDao { Object find(Long id); }");XmlTag root=sqlMap("sql/People.xml","<sqlMap namespace='PersonDao'><select id='find'>SELECT #id#</select></sqlMap>");
        assertNull(MapperModel.mapperClass(root));assertEquals(0,MapperModel.statements(dao().findMethodsByName("find",false)[0]).size());
    }
    public void testLegacyInlineParametersAndLocalAlias(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<sqlMap namespace='legacy.People'><typeAlias alias='PersonParam' type='legacy.test.Person'/><select id='lookup' parameterClass='PersonParam'>SELECT #na<caret>#</select></sqlMap>");
        var items=myFixture.completeBasic();assertNotNull(items);assertTrue(Arrays.stream(items).anyMatch(i->i.getLookupString().equals("name")));
    }
    public void testLegacyPreviewOfSelectedSqlMap()throws Exception{
        myFixture.configureByText(XmlFileType.INSTANCE,"<sqlMap namespace='legacy.People'><select id='lookup'>SELECT * FROM users <dynamic prepend='WHERE'><isNotNull property='name' prepend='AND'> name=#name# </isNotNull></dynamic></select></sqlMap>");
        XmlTag statement=((XmlFile)myFixture.getFile()).getRootTag().findFirstSubTag("select");
        var result=dev.mybatis.assistant.core.SqlRenderer.render(myFixture.getFile().getText(),statement.getAttributeValue("id"),"{\"name\":\"张三\"}",List.of());
        assertEquals("SELECT * FROM users WHERE name=?",result.sql().replaceAll("\\s+"," ").trim());assertEquals(List.of("张三"),result.parameters());
    }
    public void testLegacyDynamicPropertyCompletion(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<sqlMap namespace='legacy.People'><select id='lookup' parameterClass='legacy.test.Person'><dynamic prepend='WHERE'><isNotNull property='na<caret>'>name=#name#</isNotNull></dynamic></select></sqlMap>");
        var items=myFixture.completeBasic();assertNotNull(items);assertTrue(Arrays.stream(items).anyMatch(i->i.getLookupString().equals("name")));
    }
    public void testLegacyParameterMapAndResultMapTypes(){
        XmlTag root=sqlMap("sql/People.xml","<sqlMap namespace='legacy.People'><typeAlias alias='PersonAlias' type='legacy.test.Person'/><parameterMap id='args' class='PersonAlias'><parameter property='name' jdbcType='VARCHAR'/></parameterMap><resultMap id='result' class='PersonAlias'><result property='name' column='name'/></resultMap><select id='lookup' parameterMap='args' resultMap='result'>SELECT #name#</select></sqlMap>");
        var model=ParameterModel.of(null,root.findFirstSubTag("select"));assertNotNull(model.resolve("name"));
        assertNotNull(MappingTypes.ownerType(root.findFirstSubTag("resultMap").findFirstSubTag("result")));
        assertNotNull(MappingTypes.ownerType(root.findFirstSubTag("parameterMap").findFirstSubTag("parameter")));
    }
    public void testLegacyPlaceholderNavigationWithJdbcSuffix(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<sqlMap namespace='legacy.People'><select id='lookup' parameterClass='legacy.test.Person'>SELECT #na<caret>me:VARCHAR#</select></sqlMap>");int offset=myFixture.getCaretOffset();
        var targets=new PlaceholderGotoDeclaration().getGotoDeclarationTargets(myFixture.getFile().findElementAt(offset),offset,myFixture.getEditor());assertNotNull(targets);assertEquals("name",((PsiField)targets[0]).getName());
    }
    public void testLegacyIterateElementBindings(){
        XmlTag root=sqlMap("sql/People.xml","<sqlMap namespace='legacy.test.PersonDao'><select id='findMany'><iterate open='(' close=')' conjunction=','>#[].name#</iterate></select></sqlMap>");
        XmlTag iterate=root.findFirstSubTag("select").findFirstSubTag("iterate");assertNotNull(ParameterModel.of(MapperModel.method(iterate.getParentTag()),iterate).resolve("[].name"));
    }
    public void testLegacyDaoDoesNotEnforceProxyContract(){
        sqlMap("sql/People.xml","<sqlMap namespace='legacy.test.PersonDao'><select id='differentSqlName' parameterClass='legacy.test.Person'>SELECT #name#</select></sqlMap>");
        myFixture.enableInspections(new MapperInspection(),new MapperMethodInspection());myFixture.configureByText(XmlFileType.INSTANCE,"<sqlMap namespace='legacy.test.PersonDao'><select id='differentSqlName' parameterClass='legacy.test.Person'>SELECT #name#</select></sqlMap>");
        assertTrue(myFixture.doHighlighting().stream().noneMatch(w->w.getDescription()!=null && w.getDescription().contains("Mapper 接口缺少方法")));
    }
    public void testSqlClientStringNavigationAndAmbiguousBareId(){
        sqlMap("sql/One.xml","<sqlMap namespace='one'><select id='lookup'>SELECT 1</select></sqlMap>");sqlMap("sql/Two.xml","<sqlMap namespace='two'><select id='lookup'>SELECT 2</select></sqlMap>");
        assertEquals(2,MapperModel.sqlTargets(getProject(),"lookup").size());
        myFixture.configureByText("Calls.java","import com.ibatis.sqlmap.client.SqlMapClient; class Calls { Object run(SqlMapClient client) { return client.queryForObject(\"one.look<caret>up\", null); } }");
        PsiLiteralExpression literal=PsiTreeUtil.getParentOfType(myFixture.getFile().findElementAt(myFixture.getCaretOffset()),PsiLiteralExpression.class,false);assertNotNull(literal);assertTrue(SqlApiReferences.isStatementArgument(literal));
        assertTrue(Arrays.stream(literal.getReferences()).anyMatch(r->r.resolve() instanceof XmlAttributeValue));
    }
    public void testInterfaceFindsImplementationConstantAndHelper(){
        XmlTag root=sqlMap("sql/People.xml","<sqlMap namespace='legacy.People'><select id='lookup' resultClass='legacy.test.Person'>SELECT 1</select></sqlMap>");
        PsiClass impl=myFixture.addClass("package legacy.test; public class PersonDaoImpl implements PersonDao { private static final String NS=\"legacy.People\"; private com.ibatis.sqlmap.client.SqlMapClient client; public Person load(Person query){return lookup(query);} private Person lookup(Person query){return (Person)client.queryForObject(NS+\".lookup\",query);} public Person find(Long id){return null;} public java.util.List<Person> findMany(java.util.List<Person> people){return null;} public void helper(){} }");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());PsiMethod contract=dao().findMethodsByName("load",false)[0];
        assertFalse("implementation should be found by the Java index",com.intellij.psi.search.searches.OverridingMethodsSearch.search(contract,GlobalSearchScope.projectScope(getProject()),true).findAll().isEmpty());
        assertEquals("direct implementation navigation",1,LegacyNavigation.collectTargets(impl.findMethodsByName("load",false)[0]).size());
        var targets=LegacyNavigation.collectTargets(contract);assertEquals(1,targets.size());assertEquals(root.findFirstSubTag("select"),targets.getFirst());
    }
    public void testMyBatisSqlSessionQualifiedIdNavigation(){
        sqlMap("sql/Modern.xml","<mapper namespace='modern.PersonMapper'><select id='lookup'>SELECT 1</select></mapper>");
        myFixture.addClass("package org.apache.ibatis.session; public interface SqlSession { Object selectOne(String statement,Object parameter); }");
        myFixture.configureByText("ModernCalls.java","import org.apache.ibatis.session.SqlSession; class ModernCalls { Object run(SqlSession session) { return session.selectOne(\"modern.PersonMapper.look<caret>up\", null); } }");
        PsiLiteralExpression literal=PsiTreeUtil.getParentOfType(myFixture.getFile().findElementAt(myFixture.getCaretOffset()),PsiLiteralExpression.class,false);assertNotNull(literal);assertTrue(Arrays.stream(literal.getReferences()).anyMatch(r->r.resolve() instanceof XmlAttributeValue));
    }
}
