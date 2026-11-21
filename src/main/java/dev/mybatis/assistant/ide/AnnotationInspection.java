package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import java.util.regex.Matcher;
import org.jetbrains.annotations.NotNull;

public final class AnnotationInspection extends AbstractBaseJavaLocalInspectionTool {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,boolean onTheFly){
        if(!AssistantSettings.get(holder.getProject()).inspections)return PsiElementVisitor.EMPTY_VISITOR;
        return new JavaElementVisitor(){
            @Override public void visitLiteralExpression(@NotNull PsiLiteralExpression literal){
                PsiMethod method=AnnotationSql.method(literal);String sql=AnnotationSql.text(literal);if(method==null || sql==null || AnnotationSql.scripted(method))return;
                ParameterModel model=ParameterModel.of(method,null);Matcher m=MapperReferences.PLACEHOLDER.matcher(sql);
                while(m.find()){
                    TextRange range=AnnotationSql.range(literal,m.start(1),m.end(1));if(range==null)continue;
                    if(!model.dynamic() && model.resolve(m.group(1))==null)holder.registerProblem(literal,range,"找不到注解 SQL 参数或属性: "+m.group(1));
                    if(sql.charAt(m.start())=='$' && AssistantSettings.get(holder.getProject()).warnDollar)holder.registerProblem(literal,range,"${} 直接拼接 SQL，请确认输入来自可信白名单");
                }
            }
        };
    }
}
