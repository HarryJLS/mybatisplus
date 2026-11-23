package dev.mybatis.assistant.ide.kotlin;

import com.intellij.codeInsight.daemon.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import dev.mybatis.assistant.ide.*;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class KotlinMapperActionsGutter implements LineMarkerProvider {
    @Override public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element){return null;}
    @Override public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,@NotNull Collection<? super LineMarkerInfo<?>> markers){
        for(PsiElement element:elements){
            if(element.getFirstChild()!=null || DumbService.isDumb(element.getProject()) || !AssistantSettings.get(element.getProject()).gutter)continue;
            if(element.getParent() instanceof KtNamedFunction function && function.getNameIdentifier()==element){
                var method=new KotlinMapperSupport().method(function);if(method==null)continue;
                var marker=MapperActionsGutter.methodMarker(element,method);if(marker!=null)markers.add(marker);
            }
        }
    }
}
