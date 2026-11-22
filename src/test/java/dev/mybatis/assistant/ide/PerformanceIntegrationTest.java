package dev.mybatis.assistant.ide;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;

/** Reuse must coexist with cross-file invalidation and inherited generic types. */
public final class PerformanceIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected void setUp()throws Exception{
        super.setUp();
        myFixture.addClass("package org.apache.ibatis.type; public @interface Alias { String value(); }");
        myFixture.addClass("package sample; @org.apache.ibatis.type.Alias(\"Person\") public class User { public String name; }");
    }
    private void edit(PsiFile file,String text){
        WriteCommandAction.runWriteCommandAction(getProject(),()->PsiDocumentManager.getInstance(getProject()).getDocument(file).setText(text));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }
    private void indexed(){
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    }
    public void testModuleAliasesSharedAndRetainedAcrossSqlEdits(){
        myFixture.addFileToProject("application.properties","mybatis.type-aliases-package=sample");
        PsiFile first=myFixture.addFileToProject("mapper/First.xml","<mapper namespace='sample.First'><select id='find'>SELECT 1</select></mapper>");
        PsiFile second=myFixture.addFileToProject("mapper/Second.xml","<mapper namespace='sample.Second'/>");indexed();
        Map<String,String> aliases=TypeAliases.names(first);assertEquals("sample.User",aliases.get("person"));
        assertSame(aliases,TypeAliases.names(second));
        edit(first,"<mapper namespace='sample.First'><select id='find'>SELECT 2</select></mapper>");
        assertSame("SQL PSI edits must not invalidate a module's type aliases",aliases,TypeAliases.names(second));
    }
    public void testAliasAnnotationEditInvalidatesSharedCache(){
        myFixture.addFileToProject("application.properties","mybatis.type-aliases-package=sample");
        PsiFile file=myFixture.addFileToProject("mapper/Alias.xml","<mapper namespace='sample.Alias'/>");indexed();
        assertEquals("sample.User",TypeAliases.resolve(file,"person"));
        PsiClass user=MapperModel.classByName(getProject(),"sample.User");
        edit(user.getContainingFile(),"package sample; @org.apache.ibatis.type.Alias(\"Customer\") public class User { public String name; }");indexed();
        assertNull(TypeAliases.resolve(file,"person"));assertEquals("sample.User",TypeAliases.resolve(file,"customer"));
    }
    public void testMemberTablesReusedAcrossSqlEdits(){
        PsiFile sql=myFixture.addFileToProject("mapper/Member.xml","<mapper namespace='sample.Member'/>");indexed();
        PsiClass user=MapperModel.classByName(getProject(),"sample.User");
        PsiType type=JavaPsiFacade.getElementFactory(getProject()).createType(user);
        Map<String,ParameterModel.Symbol> members=ParameterModel.properties(type);assertNotNull(members.get("name"));
        for(int i=0;i<1000;i++)assertSame(members,ParameterModel.properties(type));
        edit(sql,"<mapper namespace='sample.Member'><select id='find'>SELECT 1</select></mapper>");
        assertSame(members,ParameterModel.properties(type));
    }
    public void testGenericArgumentsDoNotShareSubstitutedTypes(){
        PsiClass base=myFixture.addClass("package sample; public class Base<T> { public T value; public T getPayload(){return null;} }");
        PsiClass box=myFixture.addClass("package sample; public class Box<E> extends Base<E> {}");
        var factory=JavaPsiFacade.getElementFactory(getProject());
        PsiType users=factory.createTypeFromText("sample.Box<sample.User>",box);
        PsiType strings=factory.createTypeFromText("sample.Box<java.lang.String>",box);
        assertEquals("sample.User",ParameterModel.properties(users).get("value").type().getCanonicalText());
        assertEquals("java.lang.String",ParameterModel.properties(strings).get("payload").type().getCanonicalText());
        assertEquals("sample.User",ParameterModel.properties(users).get("payload").type().getCanonicalText());
        edit(base.getContainingFile(),"package sample; public class Base<T> { public T changed; }");
        assertNull(ParameterModel.properties(users).get("value"));assertNotNull(ParameterModel.properties(users).get("changed"));
    }
    public void testProjectionRetainedAcrossUnrelatedJavaEdits(){
        XmlFile file=(XmlFile)myFixture.addFileToProject("mapper/Projection.xml","<mapper namespace='sample.Projection'><select id='find'>SELECT name FROM users</select></mapper>");indexed();
        XmlTag statement=file.getRootTag().findFirstSubTag("select");var projection=StatementSql.projection(statement);
        edit(MapperModel.classByName(getProject(),"sample.User").getContainingFile(),"package sample; public class User { public String changed; }");
        assertSame(projection,StatementSql.projection(statement));
    }
    public void testIncludeEditInvalidatesProjectionAndResultMapColumns(){
        XmlFile other=(XmlFile)myFixture.addFileToProject("mapper/Fragments.xml","<mapper namespace='sample.Fragments'><sql id='columns'>name</sql></mapper>");
        XmlFile file=(XmlFile)myFixture.addFileToProject("mapper/Projection.xml","<mapper namespace='sample.Projection'><resultMap id='r' type='sample.User'/><select id='find' resultMap='r'>SELECT <include refid='sample.Fragments.columns'/> FROM users</select></mapper>");indexed();
        XmlTag statement=file.getRootTag().findFirstSubTag("select"),map=file.getRootTag().findFirstSubTag("resultMap");
        assertEquals(List.of("name"),StatementSql.projection(statement).columns());assertEquals(List.of("name"),StatementSql.columns(map).columns());
        WriteCommandAction.runWriteCommandAction(getProject(),()->{other.getRootTag().findFirstSubTag("sql").getValue().setText("id, name");});
        assertEquals(List.of("id","name"),StatementSql.projection(statement).columns());assertEquals(List.of("id","name"),StatementSql.columns(map).columns());
    }
    public void testMissingIncludeIsRetriedAfterIndexChange(){
        XmlFile file=(XmlFile)myFixture.addFileToProject("mapper/Missing.xml","<mapper namespace='sample.Missing'><select id='find'>SELECT <include refid='sample.New.columns'/> FROM users</select></mapper>");indexed();
        XmlTag statement=file.getRootTag().findFirstSubTag("select");assertFalse(StatementSql.projection(statement).complete());
        myFixture.addFileToProject("mapper/New.xml","<mapper namespace='sample.New'><sql id='columns'>name</sql></mapper>");indexed();
        assertTrue(StatementSql.projection(statement).complete());assertEquals(List.of("name"),StatementSql.projection(statement).columns());
    }
}
