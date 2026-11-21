package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import java.util.*;

public final class MapperDiagnostics {
    private MapperDiagnostics() {}
    public record Issue(String message,int offset) {}
    public record Result(List<Issue> issues,List<String> checksExecuted,List<String> checksSkipped) {}
    public static Result inspect(PsiFile file){
        if(com.intellij.openapi.project.DumbService.isDumb(file.getProject()))throw new IllegalArgumentException("IDEA 正在更新索引，请稍后校验");
        if(MapperModel.root(file)==null)throw new IllegalArgumentException("静态校验入口需要 Mapper XML");
        List<Issue> issues=new ArrayList<>();ProblemsHolder holder=new ProblemsHolder(InspectionManager.getInstance(file.getProject()),file,false);
        var options=AssistantSettings.get(file.getProject());List<String> executed=new ArrayList<>(),skipped=new ArrayList<>();
        if(options.inspections){
            PsiElementVisitor visitor=new MapperInspection().buildVisitor(holder,false);Collection<XmlTag> tags=PsiTreeUtil.findChildrenOfType(file,XmlTag.class);Collection<XmlText> texts=PsiTreeUtil.findChildrenOfType(file,XmlText.class);
            if(tags.size()+texts.size()>16384)throw new IllegalArgumentException("XML 节点数量超过检查上限");
            for(XmlTag tag:tags){ProgressManager.checkCanceled();tag.accept(visitor);}for(XmlText text:texts){ProgressManager.checkCanceled();text.accept(visitor);}
            for(ProblemDescriptor descriptor:holder.getResults())issues.add(new Issue(descriptor.getDescriptionTemplate(),descriptor.getPsiElement().getTextRange().getStartOffset()));
            executed.addAll(List.of("映射 id 与引用","已知参数和实体属性","Mapper/XML 方法对应"));
            if(options.ognlChecks && !MapperModel.isLegacy(MapperModel.root(file)))executed.add("OGNL 语法和已知属性/方法");else skipped.add("OGNL 检查关闭或旧 iBATIS 语法");
            if(options.columnChecks)executed.add("明确 SELECT 投影与 resultMap 列");else skipped.add("resultMap 列检查关闭");
        }else skipped.add("映射检查已在设置中关闭");
        skipped.addAll(List.of("数据库表/字段语义","所有动态 SQL 分支","自定义 TypeHandler 与业务断言"));
        return new Result(List.copyOf(issues),List.copyOf(executed),List.copyOf(skipped));
    }
}
