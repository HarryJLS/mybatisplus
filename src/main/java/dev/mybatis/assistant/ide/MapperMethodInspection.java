package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

public final class MapperMethodInspection extends AbstractBaseJavaLocalInspectionTool {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,boolean onTheFly){
        if(!AssistantSettings.get(holder.getProject()).inspections || DumbService.isDumb(holder.getProject()))return PsiElementVisitor.EMPTY_VISITOR;
        return new JavaElementVisitor(){
            @Override public void visitMethod(@NotNull PsiMethod m){
                PsiClass cls=m.getContainingClass();
                if(m.isConstructor() || m.hasModifierProperty(PsiModifier.DEFAULT) || m.hasModifierProperty(PsiModifier.STATIC) || !MapperModel.mapper(cls) || MapperModel.annotated(m) || m.getNameIdentifier()==null)return;
                // iBATIS DAO methods may delegate to differently named statements; there is no proxy contract.
                if(MapperModel.mapperRoots(cls).stream().anyMatch(MapperModel::isLegacy))return;
                // Only methods declared here are inspected; framework-provided inherited CRUD is untouched.
                if(MapperModel.statements(m).isEmpty())holder.registerProblem(m.getNameIdentifier(),"缺少 MyBatis XML 语句: "+m.getName(),new CreateStatementFix());
            }
        };
    }
    private static final class CreateStatementFix implements LocalQuickFix {
        @Override public @NotNull String getFamilyName(){return "生成 MyBatis XML 语句骨架";}
        @Override public void applyFix(@NotNull com.intellij.openapi.project.Project p,@NotNull ProblemDescriptor d){
            PsiElement e=d.getPsiElement();if(e.getParent() instanceof PsiMethod m)GenerateStatementIntention.generate(m);
        }
    }
}
