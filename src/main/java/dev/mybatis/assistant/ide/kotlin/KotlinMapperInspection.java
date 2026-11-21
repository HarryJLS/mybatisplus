package dev.mybatis.assistant.ide.kotlin;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import dev.mybatis.assistant.ide.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;

public final class KotlinMapperInspection extends LocalInspectionTool {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,boolean onTheFly){
        if(!AssistantSettings.get(holder.getProject()).inspections)return PsiElementVisitor.EMPTY_VISITOR;
        return new KtVisitorVoid(){
            @Override public void visitNamedFunction(@NotNull KtNamedFunction function){
                PsiMethod method=LightClassUtilsKt.getRepresentativeLightMethod(function);
                if(method==null || !MapperModel.mapper(method.getContainingClass()) || function.getNameIdentifier()==null)return;
                if(function.hasModifier(KtTokens.SUSPEND_KEYWORD)){holder.registerProblem(function.getNameIdentifier(),"MyBatis Mapper 的 suspend 方法未支持，请使用普通接口方法");return;}
                if(function.hasBody() || MapperModel.annotated(method) || MapperModel.mapperRoots(method.getContainingClass()).stream().anyMatch(MapperModel::isLegacy))return;
                if(MapperModel.statements(method).isEmpty())holder.registerProblem(function.getNameIdentifier(),"缺少 MyBatis XML 语句: "+function.getName());
            }
        };
    }
}
