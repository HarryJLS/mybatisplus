package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;

public final class MapperActionsGutterIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private final MapperActionsGutter provider=new MapperActionsGutter();
    private XmlTag xml(String body){XmlFile file=(XmlFile)myFixture.addFileToProject("mapper/Actions.xml","<mapper namespace='sample.Queries'>"+body+"</mapper>");IndexingTestUtil.waitUntilIndexesAreReady(getProject());return file.getRootTag();}
    private List<LineMarkerInfo<?>> markers(PsiElement root){List<LineMarkerInfo<?>> result=new ArrayList<>();provider.collectSlowLineMarkers(Arrays.asList(PsiTreeUtil.collectElements(root,e->e.getFirstChild()==null)),result);return result;}
    public void testSelectButtonIsOnOpeningNameWithoutAttributeOrClosingDuplicates(){
        myFixture.addClass("package sample; public interface Queries { Object find(); void write(); }");
        XmlTag root=xml("\n<select\n id='find' resultType='map'>SELECT 1</select>\n<insert id='write'>INSERT INTO t VALUES (1)</insert>");
        var buttons=markers(root.getContainingFile());assertEquals(1,buttons.size());var button=buttons.getFirst();assertEquals("select",button.getElement().getText());assertEquals(root.findFirstSubTag("select").getTextRange().getStartOffset()+1,button.getElement().getTextRange().getStartOffset());assertSame(AllIcons.Actions.Execute,button.getIcon());assertNotNull(button.getNavigationHandler());
        List<LineMarkerInfo<?>> fish=new ArrayList<>();new MapperGutter().collectSlowLineMarkers(Arrays.asList(PsiTreeUtil.collectElements(root.getContainingFile(),e->e.getFirstChild()==null)),fish);assertEquals("existing namespace and statement navigation remains intact",3,fish.size());
    }
    public void testMappedJavaMethodGetsOneButtonWhileUnmappedMethodAndClassDoNot(){
        PsiClass cls=myFixture.addClass("package sample; public interface Queries { Object find(); Object absent(); }");xml("<select id='find'>SELECT 1</select>");
        var buttons=markers(cls.getContainingFile());assertEquals(1,buttons.size());assertSame(cls.findMethodsByName("find",false)[0].getNameIdentifier(),buttons.getFirst().getElement());assertEquals("测试 Mapper SQL / 生成 testcase",buttons.getFirst().getLineMarkerTooltip());assertNotNull(buttons.getFirst().getNavigationHandler());
    }
    public void testSelectPopupOffersResultMapAndMapperTest(){
        myFixture.addClass("package sample; public interface Queries { Object find(); }");XmlTag select=xml("<select id='find'>SELECT 1</select>").findFirstSubTag("select");
        var actions=MapperActionsGutter.actionsForSelect(select).getChildren((AnActionEvent)null);assertEquals(List.of("从 SELECT 生成 resultMap（预览）","测试 Mapper SQL / 生成 testcase…"),Arrays.stream(actions).map(a->a.getTemplatePresentation().getText()).toList());
        for(AnAction action:actions){var event=AnActionEvent.createFromAnAction(action,null,ActionPlaces.UNKNOWN,DataContext.EMPTY_CONTEXT);action.update(event);assertTrue(event.getPresentation().isEnabled());}
        WriteCommandAction.runWriteCommandAction(getProject(),select::delete);var event=AnActionEvent.createFromAnAction(actions[0],null,ActionPlaces.UNKNOWN,DataContext.EMPTY_CONTEXT);actions[0].update(event);assertFalse("closed/deleted targets must disable the popup action",event.getPresentation().isEnabled());
    }
    public void testSelectWithoutInterfaceOnlyOffersResultMap(){
        XmlTag select=xml("<select id='find' resultType='map'>SELECT 1</select>").findFirstSubTag("select");assertEquals(1,MapperActionsGutter.actionsForSelect(select).getChildren((AnActionEvent)null).length);assertEquals(1,markers(select).size());
    }
    public void testGutterSettingAndLegacyDoNotAddButtons(){
        PsiClass cls=myFixture.addClass("package sample; public interface Queries { Object find(); }");XmlTag root=xml("<select id='find'>SELECT 1</select>");var options=AssistantSettings.get(getProject());boolean previous=options.gutter;
        try{options.gutter=false;assertTrue(markers(root).isEmpty());assertTrue(markers(cls.getContainingFile()).isEmpty());}finally{options.gutter=previous;}
        WriteCommandAction.runWriteCommandAction(getProject(),()->{root.setName("sqlMap");});assertTrue(markers(root).isEmpty());assertTrue(markers(cls.getContainingFile()).isEmpty());
    }
}
