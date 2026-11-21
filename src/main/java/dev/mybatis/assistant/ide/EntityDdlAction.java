package dev.mybatis.assistant.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import dev.mybatis.assistant.core.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class EntityDdlAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(@NotNull AnActionEvent e){e.getPresentation().setEnabled(e.getProject()!=null && !DumbService.isDumb(e.getProject()));}
    @Override public void actionPerformed(@NotNull AnActionEvent e){
        var project=e.getProject();var editor=e.getData(CommonDataKeys.EDITOR);PsiFile file=e.getData(CommonDataKeys.PSI_FILE);PsiElement element=e.getData(CommonDataKeys.PSI_ELEMENT);
        if(element==null && file!=null && editor!=null)element=file.findElementAt(editor.getCaretModel().getOffset());
        PsiClass cls=element==null?null:PsiTreeUtil.getParentOfType(element,PsiClass.class,false);
        if(project==null)return;if(cls==null){Messages.showInfoMessage(project,"请把光标放在 Java 实体类内。","MyBatis Assistant");return;}
        List<Schema.Column> columns=new ArrayList<>();List<String> skipped=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(PsiField field:cls.getAllFields()){
            if(field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT))continue;
            PsiAnnotation annotation=field.getAnnotation("com.baomidou.mybatisplus.annotation.TableField");
            if(annotation!=null && annotation.findAttributeValue("exist") instanceof PsiLiteralExpression v && Boolean.FALSE.equals(v.getValue()))continue;
            String type=DdlGenerator.sqlType(field.getType().getCanonicalText());if(type==null){skipped.add(field.getName());continue;}
            String column=stringValue(annotation);PsiAnnotation key=field.getAnnotation("com.baomidou.mybatisplus.annotation.TableId");
            if(key!=null && stringValue(key)!=null)column=stringValue(key);if(column==null || column.isBlank())column=Schema.snake(field.getName());
            if(!seen.add(column))continue;
            boolean primary=key!=null || field.getName().equals("id");boolean auto=key!=null && key.findAttributeValue("type")!=null && key.findAttributeValue("type").getText().endsWith("AUTO");
            columns.add(new Schema.Column(column,type,primary,auto,!primary && !(field.getType() instanceof PsiPrimitiveType)));
        }
        String table=stringValue(cls.getAnnotation("com.baomidou.mybatisplus.annotation.TableName"));if(table==null || table.isBlank())table=Schema.snake(Objects.requireNonNullElse(cls.getName(),"entity"));
        try{String sql=DdlGenerator.generate(new Schema.Table(table,columns),true);if(!skipped.isEmpty())sql="-- 以下复杂类型字段未自动映射: "+String.join(", ",skipped)+"\n"+sql;new TextPreviewDialog(project,"实体生成 DDL（MySQL；请检查类型与约束）",sql).show();}
        catch(IllegalArgumentException ex){Messages.showErrorDialog(project,ex.getMessage(),"MyBatis Assistant");}
    }
    private static String stringValue(PsiAnnotation annotation){if(annotation==null)return null;var v=annotation.findAttributeValue("value");return v instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s?s:null;}
}
