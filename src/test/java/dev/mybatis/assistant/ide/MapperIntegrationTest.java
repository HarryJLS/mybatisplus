package dev.mybatis.assistant.ide;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.IndexingTestUtil;
import java.util.*;

/** Runs inside a real IntelliJ test application with the plugin descriptor loaded. */
public final class MapperIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private boolean previousAutocomplete;
    @Override protected void setUp()throws Exception{
        super.setUp();
        previousAutocomplete=com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION;
        com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION=false;
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}");
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Param { String value(); }");
        myFixture.addClass("package sample; public class User { public Long id; public String name; public Address address; }");
        myFixture.addClass("package sample; public class Address { public String city; }");
        myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface UserMapper { User findById(@org.apache.ibatis.annotations.Param(\"id\") Long id); User search(User user); User findMany(@org.apache.ibatis.annotations.Param(\"users\") java.util.List<User> users); }");
    }
    @Override protected void tearDown()throws Exception{
        try{com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION=previousAutocomplete;}
        finally{super.tearDown();}
    }
    public void testInheritedGenericProperty(){
        myFixture.addClass("package sample; public class Base<T> { public T payload; }");
        PsiClass child=myFixture.addClass("package sample; public class UserEnvelope extends Base<User> {}");
        var type=JavaPsiFacade.getElementFactory(getProject()).createType(child);
        var property=ParameterModel.properties(type).get("payload");assertNotNull(property);assertEquals("sample.User",property.type().getCanonicalText());
    }
    public void testNativeEngineLoadsOnlyWhenUsed(){
        assertFalse(dev.mybatis.assistant.core.NativeBridge.isLoaded());
        AssistantPanel panel=new AssistantPanel(getProject());assertEquals(1,panel.getComponentCount());
        assertFalse("opening the tool window should not load Rust",dev.mybatis.assistant.core.NativeBridge.isLoaded());
        assertEquals("select 1;",dev.mybatis.assistant.core.NativeBridge.logsToSql("Preparing: select ?\nParameters: 1(Integer)"));
        assertTrue(dev.mybatis.assistant.core.NativeBridge.isLoaded());
    }
    public void testParameterBindingsSharedAcrossXmlNodes(){
        PsiFile xml=myFixture.addFileToProject("mapper/Shared.xml","<mapper namespace='sample.UserMapper'><select id='search'>SELECT name FROM users <if test='name != null'>WHERE name=#{name}</if></select></mapper>");
        XmlTag statement=((XmlFile)xml).getRootTag().findFirstSubTag("select");PsiMethod method=MapperModel.method(statement);
        ParameterModel first=ParameterModel.of(method,statement),second=ParameterModel.of(method,statement.findFirstSubTag("if"));
        assertSame("ordinary XML nodes should share one method binding model",first,second);
    }
    public void testPlaceholderNavigation(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><select id='search'>SELECT #{na<caret>me}</select></mapper>");
        int offset=myFixture.getCaretOffset();PsiElement source=myFixture.getFile().findElementAt(offset);
        PsiElement[] targets=new PlaceholderGotoDeclaration().getGotoDeclarationTargets(source,offset,myFixture.getEditor());
        assertNotNull(targets);assertEquals(1,targets.length);assertTrue(targets[0] instanceof PsiField);assertEquals("name",((PsiField)targets[0]).getName());
    }
    public void testPropertyRenameUpdatesXml(){
        PsiFile xml=myFixture.addFileToProject("mapper/Rename.xml","<mapper namespace='sample.UserMapper'><resultMap id='result' type='sample.User'><result column='name' property='name'/></resultMap><select id='search'>SELECT #{name}</select></mapper>");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        PsiClass model=JavaPsiFacade.getInstance(getProject()).findClass("sample.User",com.intellij.psi.search.GlobalSearchScope.allScope(getProject()));assertNotNull(model);
        myFixture.renameElement(model.findFieldByName("name",false),"displayName");
        assertEquals("displayName",((XmlFile)xml).getRootTag().findFirstSubTag("resultMap").findFirstSubTag("result").getAttributeValue("property"));
        assertTrue(xml.getText(),xml.getText().contains("#{displayName}"));
    }
    public void testNamespaceIndexAndNavigation(){
        PsiFile file=myFixture.addFileToProject("mapper/UserMapper.xml","<mapper namespace='sample.UserMapper'><select id='findById'>SELECT #{id}</select></mapper>");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var roots=MapperModel.xmls(getProject(),"sample.UserMapper");assertEquals(1,roots.size());
        XmlTag statement=((XmlFile)file).getRootTag().findFirstSubTag("select");
        assertEquals("findById",MapperModel.method(statement).getName());
        boolean resolved=Arrays.stream(statement.getAttribute("id").getValueElement().getReferences()).anyMatch(r->r.resolve() instanceof PsiMethod);
        assertTrue("XML id should navigate to a Java mapper method",resolved);
    }
    public void testBeanParameterCompletion(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><select id='search'>SELECT #{na<caret>}</select></mapper>");
        var items=myFixture.completeBasic();assertNotNull(items);
        assertTrue(Arrays.stream(items).anyMatch(i->i.getLookupString().equals("name")));
        XmlTag statement=((XmlFile)myFixture.getFile()).getRootTag().findFirstSubTag("select");
        var model=ParameterModel.of(MapperModel.method(statement),statement);
        assertNotNull(model.resolve("name"));assertNull("single bean is directly bound, not prefixed by Java parameter name",model.resolve("user.name"));
    }
    public void testForeachCompletion(){
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><select id='findMany'><foreach collection='users' item='u'>#{u.na<caret>}</foreach></select></mapper>");
        LookupElement[] items=myFixture.completeBasic();assertNotNull(items);
        assertTrue(Arrays.stream(items).anyMatch(i->i.getLookupString().equals("name")));
    }
    public void testNestedPropertyAndIndexedList(){
        PsiClass cls=JavaPsiFacade.getInstance(getProject()).findClass("sample.UserMapper",com.intellij.psi.search.GlobalSearchScope.allScope(getProject()));assertNotNull(cls);
        var bean=ParameterModel.of(cls.findMethodsByName("search",false)[0],null);assertNotNull(bean.resolve("address.city"));assertNull(bean.resolve("address.missing"));
        var list=ParameterModel.of(cls.findMethodsByName("findMany",false)[0],null);assertNotNull(list.resolve("users[0].name"));
    }
    public void testPropertyInspection(){
        myFixture.enableInspections(new MapperInspection());
        myFixture.configureByText(XmlFileType.INSTANCE,"<mapper namespace='sample.UserMapper'><resultMap id='result' type='sample.User'><result column='name' property='missing'/></resultMap><select id='findById' resultMap='result'>SELECT #{missing}</select></mapper>");
        var warnings=myFixture.doHighlighting();assertTrue(warnings.stream().anyMatch(w->w.getDescription()!=null && w.getDescription().contains("实体中找不到属性: missing")));
        assertTrue(warnings.stream().anyMatch(w->w.getDescription()!=null && w.getDescription().contains("找不到参数或属性: missing")));
    }
    public void testNamespaceIndexUpdates(){
        PsiFile file=myFixture.addFileToProject("mapper/Dynamic.xml","<mapper namespace='sample.First'/>");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());assertEquals(1,MapperModel.xmls(getProject(),"sample.First").size());
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),()->{((XmlFile)file).getRootTag().setAttribute("namespace","sample.Second");});
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());assertEquals(0,MapperModel.xmls(getProject(),"sample.First").size());assertEquals(1,MapperModel.xmls(getProject(),"sample.Second").size());
    }
}
