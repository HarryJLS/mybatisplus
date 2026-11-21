package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.daemon.*;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class MapperGutter implements LineMarkerProvider {
    @Override public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement e){return null;}
    @Override public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,@NotNull Collection<? super LineMarkerInfo<?>> result){
        for(PsiElement e:elements){
            if(e.getFirstChild()!=null || DumbService.isDumb(e.getProject()) || !AssistantSettings.get(e.getProject()).gutter)continue;
            if(e instanceof PsiIdentifier && e.getParent() instanceof PsiClass cls && cls.isInterface() && cls.getNameIdentifier()==e){
                var roots=MapperModel.mapperRoots(cls);if(!roots.isEmpty())result.add(NavigationGutterIconBuilder.create(com.intellij.icons.AllIcons.Gutter.ImplementedMethod).setTargets(roots).setTooltipText("跳转到接口的映射 XML").createLineMarkerInfo(e));
                continue;
            }
            if(e instanceof PsiIdentifier && e.getParent() instanceof PsiMethod method && method.getNameIdentifier()==e){
                var targets=MapperModel.statements(method);if(!targets.isEmpty())result.add(NavigationGutterIconBuilder.create(com.intellij.icons.AllIcons.Gutter.ImplementedMethod).setTargets(targets).setTooltipText("跳转到映射 XML").createLineMarkerInfo(e));
                else if(MapperModel.mapperRoots(method.getContainingClass()).stream().anyMatch(MapperModel::isLegacy)){
                    var pointer=SmartPointerManager.getInstance(e.getProject()).createSmartPsiElementPointer(method);
                    result.add(new LineMarkerInfo<>(e,e.getTextRange(),com.intellij.icons.AllIcons.Actions.Find,ignored->"从 DAO 实现查找 iBATIS XML",(event,source)->{PsiMethod current=pointer.getElement();if(current!=null)LegacyNavigation.navigate(current);},com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.RIGHT,()->"查找 iBATIS XML"));
                }
            } else if(e.getParent() instanceof XmlAttributeValue value && value.getParent() instanceof XmlAttribute attr && attr.getName().equals("id") && MapperModel.isStatement(attr.getParent()) && MapperModel.root(e)!=null){
                PsiMethod method=MapperModel.method(attr.getParent());if(method!=null)result.add(NavigationGutterIconBuilder.create(com.intellij.icons.AllIcons.Gutter.ImplementingMethod).setTarget(method).setTooltipText("跳转到 Mapper 接口").createLineMarkerInfo(e));
            }
        }
    }
}
