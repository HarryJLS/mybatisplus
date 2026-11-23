package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.daemon.*;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import com.intellij.ui.awt.RelativePoint;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Declaration buttons are separate from fish navigation markers and do no generation on highlight. */
public final class MapperActionsGutter implements LineMarkerProvider {
    @Override public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element){return null;}
    @Override public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,@NotNull Collection<? super LineMarkerInfo<?>> markers){
        for(PsiElement element:elements){
            if(element.getFirstChild()!=null || DumbService.isDumb(element.getProject()) || !AssistantSettings.get(element.getProject()).gutter)continue;
            if(element instanceof PsiIdentifier && element.getParent() instanceof PsiMethod method && method.getNameIdentifier()==element){
                LineMarkerInfo<?> marker=methodMarker(element,method);if(marker!=null)markers.add(marker);
            }else if(element instanceof XmlToken && element.getParent() instanceof XmlTag tag && tag.getName().equals("select") && element.getText().equals("select") && element.getTextRange().getStartOffset()==tag.getTextRange().getStartOffset()+1){
                XmlTag root=MapperModel.root(tag);if(root==null || MapperModel.isLegacy(root))continue;
                var pointer=SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(tag);
                markers.add(new LineMarkerInfo<>(element,element.getTextRange(),AllIcons.Actions.Execute,ignored->"生成 resultMap / 测试 SQL / 生成 testcase",(event,source)->{
                    XmlTag current=pointer.getElement();if(current==null || current.getProject().isDisposed() || DumbService.isDumb(current.getProject()))return;
                    JBPopupFactory.getInstance().createActionGroupPopup("MyBatis Assistant",actionsForSelect(current),DataManager.getInstance().getDataContext(event.getComponent()),JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,true).show(new RelativePoint(event));
                },GutterIconRenderer.Alignment.LEFT,()->"MyBatis SELECT 操作"));
            }
        }
    }
    public static LineMarkerInfo<?> methodMarker(PsiElement anchor,PsiMethod method){
        PsiClass owner=method.getContainingClass();
        if(owner==null || !owner.isInterface() || DumbService.isDumb(anchor.getProject()) || !AssistantSettings.get(anchor.getProject()).gutter || MapperModel.statements(method).stream().noneMatch(tag->!MapperModel.isLegacy(MapperModel.root(tag))))return null;
        var pointer=SmartPointerManager.getInstance(method.getProject()).createSmartPsiElementPointer(method);
        return new LineMarkerInfo<>(anchor,anchor.getTextRange(),AllIcons.Actions.Execute,ignored->"测试 Mapper SQL / 生成 testcase",(event,source)->{PsiMethod current=pointer.getElement();if(current!=null)GenerateMapperTestIntention.open(current);},GutterIconRenderer.Alignment.LEFT,()->"测试 Mapper SQL / 生成 testcase");
    }
    static DefaultActionGroup actionsForSelect(XmlTag statement){
        DefaultActionGroup actions=new DefaultActionGroup();
        var statementPointer=SmartPointerManager.getInstance(statement.getProject()).createSmartPsiElementPointer(statement);
        actions.add(new AnAction("从 SELECT 生成 resultMap（预览）",null,AllIcons.FileTypes.Xml){
            @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
            @Override public void update(@NotNull AnActionEvent event){XmlTag current=statementPointer.getElement();event.getPresentation().setEnabled(current!=null && current.getName().equals("select") && !DumbService.isDumb(current.getProject()));}
            @Override public void actionPerformed(@NotNull AnActionEvent event){XmlTag current=statementPointer.getElement();if(current!=null)GenerateResultMapIntention.generate(current);}
        });
        PsiMethod method=MapperModel.method(statement);
        if(method!=null){
            var methodPointer=SmartPointerManager.getInstance(method.getProject()).createSmartPsiElementPointer(method);
            actions.add(new AnAction("测试 Mapper SQL / 生成 testcase…",null,AllIcons.Actions.Execute){
                @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
                @Override public void update(@NotNull AnActionEvent event){PsiMethod current=methodPointer.getElement();event.getPresentation().setEnabled(current!=null && !DumbService.isDumb(current.getProject()));}
                @Override public void actionPerformed(@NotNull AnActionEvent event){PsiMethod current=methodPointer.getElement();if(current!=null)GenerateMapperTestIntention.open(current);}
            });
        }
        return actions;
    }
}
