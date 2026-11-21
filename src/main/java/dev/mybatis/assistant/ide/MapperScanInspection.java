package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

public final class MapperScanInspection extends AbstractBaseJavaLocalInspectionTool {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,boolean onTheFly){
        if(DumbService.isDumb(holder.getProject()) || !AssistantSettings.get(holder.getProject()).springIntegration)return PsiElementVisitor.EMPTY_VISITOR;
        return new JavaElementVisitor(){
            @Override public void visitClass(@NotNull PsiClass cls){
                if(cls.getNameIdentifier()==null)return;var config=SpringMapperModel.config(cls);
                for(var rule:config.rules())for(String pkg:rule.packages())if(JavaPsiFacade.getInstance(cls.getProject()).findPackage(pkg)==null)holder.registerProblem(cls.getNameIdentifier(),"MapperScan 包不存在: "+pkg);
                if(config.skippedDynamic())holder.registerProblem(cls.getNameIdentifier(),"MapperScan 动态参数/自定义生成器未解析，Spring Bean 识别跳过该配置",ProblemHighlightType.WEAK_WARNING);
            }
        };
    }
}
