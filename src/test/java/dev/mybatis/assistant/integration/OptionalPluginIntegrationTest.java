package dev.mybatis.assistant.integration;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.spring.model.SpringImplicitBean;
import dev.mybatis.assistant.ide.*;
import dev.mybatis.assistant.ide.kotlin.KotlinMapperSupport;
import dev.mybatis.assistant.ide.spring.SpringMapperDiscoverer;
import org.jetbrains.kotlin.psi.KtFile;
import java.io.File;
import java.util.*;

/** Loads real bundled Kotlin/Spring/Database plugins in a separate, isolated test application. */
public final class OptionalPluginIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected void setUp()throws Exception{
        super.setUp();assertTrue(PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin")).isEnabled());
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}");
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Param { String value(); }");
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Select { String[] value(); }");
        String stdlib=java.nio.file.Path.of(System.getProperty("idea.home.path"),"plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar").toString();
        ModuleRootModificationUtil.addModuleLibrary(getModule(),"Kotlin test stdlib",List.of(VfsUtil.getUrlForLibraryRoot(new File(stdlib))),List.of());
    }
    public void testKotlinMapperXmlAndParameterProperties(){
        PsiFile kotlin=myFixture.addFileToProject("sample/KtMapper.kt","package sample\nimport org.apache.ibatis.annotations.Mapper\nimport org.apache.ibatis.annotations.Param\ndata class KtUser(val id: Long, val name: String)\n@Mapper interface KtMapper { fun find(@Param(\"user\") user: KtUser): KtUser? }\n");assertTrue(kotlin instanceof KtFile);
        XmlFile xml=(XmlFile)myFixture.addFileToProject("mapper/KtMapper.xml","<mapper namespace='sample.KtMapper'><select id='find'>SELECT #{user.name}</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        PsiClass cls=JavaPsiFacade.getInstance(getProject()).findClass("sample.KtMapper",GlobalSearchScope.allScope(getProject()));assertNotNull(cls);assertEquals(1,MapperModel.mapperRoots(cls).size());
        PsiMethod method=MapperModel.method(xml.getRootTag().findFirstSubTag("select"));assertNotNull(method);assertEquals("user",ParameterModel.paramName(method.getParameterList().getParameters()[0]));
        assertNotNull(ParameterModel.of(method,xml.getRootTag().findFirstSubTag("select")).resolve("user.name"));
    }
    public void testKotlinConstantSqlPreview()throws Exception{
        PsiFile file=myFixture.addFileToProject("sample/AnnotatedMapper.kt","package sample\nimport org.apache.ibatis.annotations.Select\nimport org.apache.ibatis.annotations.Param\ninterface AnnotatedMapper { @Select(\"SELECT #{id}\") fun find(@Param(\"id\") id: Long): Long }\n");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        PsiMethod method=new KotlinMapperSupport().methods(file).getFirst();assertEquals("SELECT #{id}",new KotlinMapperSupport().annotationSql(method));
        var input=MapperSnapshot.of(file,"find","{\"id\":3}");assertEquals(List.of(new java.math.BigDecimal(3)),input.render().parameters());
    }
    public void testKotlinMapperGetsOneTestcaseButtonOnFunctionName(){
        KtFile file=(KtFile)myFixture.addFileToProject("sample/ButtonMapper.kt","package sample\ninterface ButtonMapper { fun find(): Long; fun absent(): Long }\n");myFixture.addFileToProject("mapper/Buttons.xml","<mapper namespace='sample.ButtonMapper'><select id='find'>SELECT 1</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        List<com.intellij.codeInsight.daemon.LineMarkerInfo<?>> buttons=new ArrayList<>();new dev.mybatis.assistant.ide.kotlin.KotlinMapperActionsGutter().collectSlowLineMarkers(Arrays.asList(com.intellij.psi.util.PsiTreeUtil.collectElements(file,e->e.getFirstChild()==null)),buttons);assertEquals(1,buttons.size());assertEquals("find",buttons.getFirst().getElement().getText());assertNotNull(buttons.getFirst().getNavigationHandler());assertSame(com.intellij.icons.AllIcons.Actions.Execute,buttons.getFirst().getIcon());
    }
    public void testKotlinPropertyEditInvalidatesJavaMemberView(){
        PsiFile file=myFixture.addFileToProject("sample/MutableItem.kt","package sample\ndata class MutableItem(val name: String)\n");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        PsiClass cls=MapperModel.classByName(getProject(),"sample.MutableItem");assertNotNull(cls);
        assertNotNull(ParameterModel.properties(JavaPsiFacade.getElementFactory(getProject()).createType(cls)).get("name"));
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),()->{PsiDocumentManager.getInstance(getProject()).getDocument(file).setText("package sample\ndata class MutableItem(val title: String)\n");});
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        PsiClass changed=MapperModel.classByName(getProject(),"sample.MutableItem");assertNotNull(changed);
        var properties=ParameterModel.properties(JavaPsiFacade.getElementFactory(getProject()).createType(changed));
        assertNull(properties.get("name"));assertNotNull(properties.get("title"));
    }
    public void testKotlinFishFileClassAndGutterTargets(){
        KtFile file=(KtFile)myFixture.addFileToProject("sample/FishMapper.kt","package sample\ninterface FishMapper { fun find(): Long }\n");
        XmlFile xml=(XmlFile)myFixture.addFileToProject("mapper/Fish.xml","<mapper namespace='sample.FishMapper'><select id='find'>SELECT 1</select></mapper>");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());var provider=new dev.mybatis.assistant.ide.kotlin.KotlinMapperIconProvider();
        var cls=(org.jetbrains.kotlin.psi.KtClass)file.getDeclarations().getFirst();
        assertSame(AssistantIcons.JAVA_MAPPER,provider.getIcon(file,0));assertSame(AssistantIcons.JAVA_MAPPER,provider.getIcon(cls,0));
        var function=(org.jetbrains.kotlin.psi.KtNamedFunction)cls.getDeclarations().getFirst();
        List<com.intellij.codeInsight.daemon.LineMarkerInfo<?>> markers=new java.util.ArrayList<>();
        new dev.mybatis.assistant.ide.kotlin.KotlinMapperGutter().collectSlowLineMarkers(List.of(cls.getNameIdentifier(),function.getNameIdentifier()),markers);
        assertEquals(2,markers.size());
        for(var marker:markers){
            assertSame(AssistantIcons.XML_MAPPER,marker.getIcon());assertNotNull(marker.getNavigationHandler());
            var renderer=(com.intellij.codeInsight.navigation.NavigationGutterIconRenderer)marker.getNavigationHandler();
            assertTrue(renderer.isNavigateAction());assertEquals(1,renderer.getTargetElements().size());
            assertEquals(xml,renderer.getTargetElements().getFirst().getContainingFile());
        }
        assertTrue(com.intellij.ide.IconProvider.EXTENSION_POINT_NAME.getExtensionList().stream().anyMatch(p->p instanceof dev.mybatis.assistant.ide.kotlin.KotlinMapperIconProvider));
        assertNull(provider.getIcon(myFixture.addFileToProject("sample/OrdinaryMapper.kt","package sample\ninterface OrdinaryMapper {}\n"),0));
    }
    public void testKotlinWildcardCollectionBinding(){
        myFixture.addFileToProject("sample/ListMapper.kt","package sample\nimport org.apache.ibatis.annotations.Param\ndata class Item(val name: String)\ninterface ListMapper { fun find(@Param(\"users\") users: List<Item>): List<Item> }\n");XmlFile xml=(XmlFile)myFixture.addFileToProject("mapper/ListMapper.xml","<mapper namespace='sample.ListMapper'><select id='find'>SELECT #{users[0].name}</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var statement=xml.getRootTag().findFirstSubTag("select");assertNotNull(ParameterModel.of(MapperModel.method(statement),statement).resolve("users[0].name"));
    }
    public void testSpringBeanUsesInterfaceType(){
        assertTrue(PluginManagerCore.getPlugin(PluginId.getId("com.intellij.spring")).isEnabled());PsiClass mapper=myFixture.addClass("package sample; public interface SpringMapper {}");
        var bean=SpringImplicitBean.create("MyBatis Assistant",mapper,"springMapper");assertEquals("springMapper",bean.getBeanName());assertEquals("sample.SpringMapper",bean.getBeanType().getCanonicalText());
    }
    public void testOptionalDatabaseProviderRegistered(){assertFalse(DatabaseProvider.EP.getExtensionList().isEmpty());assertTrue(PluginManagerCore.getPlugin(PluginId.getId("com.intellij.database")).isEnabled());}
    public void testDatabaseSqlInjectionInsideDynamicBranches(){
        XmlFile file=(XmlFile)myFixture.addFileToProject("src/main/resources/mapper/Injected.xml","<mapper namespace='sample.Injected'><select id='find'>SELECT id FROM users <where><choose><when test='name != null'>AND name=#{name}</when><otherwise>AND id=#{id}</otherwise></choose></where></select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        for(var text:com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file,com.intellij.psi.xml.XmlText.class))if(text.getValue().contains("AND name=") || text.getValue().contains("AND id=")){
            var injected=com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject()).getInjectedPsiFiles(text);assertNotNull(injected);assertFalse(injected.isEmpty());String sql=injected.getFirst().first.getText();assertTrue(injected.getFirst().first.getLanguage().isKindOf(com.intellij.lang.Language.findLanguageByID("SQL")));assertTrue(sql,sql.contains("FROM users"));assertTrue(sql,sql.contains("WHERE"));assertFalse(sql,sql.contains("#{"));
        }
    }
    public void testDatabaseDynamicSqlInjectionPreservesXmlEscapesAndCdata(){
        XmlFile file=(XmlFile)myFixture.addFileToProject("src/main/resources/mapper/Escapes.xml","<mapper namespace='sample.Escapes'><select id='find'>SELECT id FROM users <where><if test='id != null'><![CDATA[AND id < #{id}]]></if><if test='name != null'>AND name &lt;&gt; #{name}</if></where></select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        for(var text:com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file,com.intellij.psi.xml.XmlText.class))if(text.getValue().contains("#{")){
            var manager=com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject());var injected=manager.getInjectedPsiFiles(text);assertNotNull(injected);assertFalse(injected.isEmpty());String sql=manager.getUnescapedText(injected.getFirst().first);assertTrue(sql,sql.contains("id < ?"));assertTrue(sql+"; host="+text.getValue(),sql.contains("name <> ?"));assertFalse(sql,sql.contains("CDATA"));
        }
    }
    public void testKotlinBindingRenamePreservesSourceVariable(){
        PsiFile file=myFixture.addFileToProject("sample/RenameMapper.kt","package sample\nimport org.apache.ibatis.annotations.Param as SqlParam\ninterface RenameMapper { fun find(@SqlParam(\"value\") input: Long): Long }\n");
        PsiFile xml=myFixture.addFileToProject("mapper/RenameMapper.xml","<mapper namespace='sample.RenameMapper'><select id='find'>SELECT #{value}</select></mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        PsiMethod method=new KotlinMapperSupport().methods(file).getFirst();ParamRenameHandler.rename(method.getParameterList().getParameters()[0],"criteria");assertTrue(file.getText(),file.getText().contains("SqlParam(\"criteria\")"));assertTrue(file.getText().contains("input: Long"));assertTrue(xml.getText(),xml.getText().contains("#{criteria}"));
    }
    public void testSpringMapperScanProviderCreatesNamedBean(){
        myFixture.addClass("package org.mybatis.spring.annotation; public @interface MapperScan { String[] value() default {}; }");
        myFixture.addClass("package sample.mapper; public interface OrderMapper {}");
        PsiClass config=myFixture.addClass("package app; @org.mybatis.spring.annotation.MapperScan(\"sample.mapper\") public class Config {}");
        var model=(com.intellij.spring.contexts.model.LocalModel<?>)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.intellij.spring.contexts.model.LocalModel.class},(proxy,method,args)->method.getName().equals("getConfig")?config:null);
        var beans=new SpringMapperDiscoverer().getCustomComponents(model);assertEquals(1,beans.size());assertEquals("orderMapper",beans.iterator().next().getBeanName());assertEquals("sample.mapper.OrderMapper",beans.iterator().next().getBeanType().getCanonicalText());
    }
    public void testSpringBootMapperExtensionRespectsExplicitScan(){
        myFixture.addClass("package org.mybatis.spring; public class SqlSessionFactoryBean {}");
        myFixture.addClass("package org.mybatis.spring.boot.autoconfigure; public class MybatisAutoConfiguration {}");
        myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication {}");
        myFixture.addClass("package app; @org.springframework.boot.autoconfigure.SpringBootApplication public class Boot {}");
        myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface AutoMapper {}");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var extension=new dev.mybatis.assistant.ide.spring.SpringMapperScanExtender();var scope=GlobalSearchScope.moduleScope(getModule());var beans=extension.getComponents(scope,getModule());assertEquals(1,beans.size());assertEquals("autoMapper",beans.iterator().next().getBeanName());
        myFixture.addClass("package org.mybatis.spring.annotation; public @interface MapperScan { String[] value() default {}; }");
        myFixture.addClass("package app; @org.mybatis.spring.annotation.MapperScan(\"excluded\") public class Explicit {}");IndexingTestUtil.waitUntilIndexesAreReady(getProject());assertTrue(extension.getComponents(scope,getModule()).isEmpty());
    }
}
