package dev.mybatis.assistant.ide.kotlin;

import com.intellij.codeInsight.daemon.*;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import dev.mybatis.assistant.ide.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.psi.*;

public final class KotlinMapperGutter implements LineMarkerProvider {
    @Override public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element){return null;}
    @Override public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,@NotNull Collection<? super LineMarkerInfo<?>> markers){
        for(PsiElement element:elements){
            if(element.getFirstChild()!=null || DumbService.isDumb(element.getProject()) || !AssistantSettings.get(element.getProject()).gutter)continue;
            if(element.getParent() instanceof KtClass cls && cls.isInterface() && cls.getNameIdentifier()==element){
                PsiClass light=LightClassUtilsKt.toLightClass(cls);if(light==null)continue;var targets=MapperModel.mapperRoots(light);
                if(!targets.isEmpty())markers.add(NavigationGutterIconBuilder.create(com.intellij.icons.AllIcons.Gutter.ImplementedMethod).setTargets(targets).setTooltipText("跳转到 Kotlin Mapper XML").createLineMarkerInfo(element));
            }else if(element.getParent() instanceof KtNamedFunction function && function.getNameIdentifier()==element){
                PsiMethod method=new KotlinMapperSupport().method(function);if(method==null)continue;var targets=MapperModel.statements(method);
                if(!targets.isEmpty())markers.add(NavigationGutterIconBuilder.create(com.intellij.icons.AllIcons.Gutter.ImplementedMethod).setTargets(targets).setTooltipText("跳转到 Mapper XML").createLineMarkerInfo(element));
            }
        }
    }
}
