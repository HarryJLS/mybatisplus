package dev.mybatis.assistant.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.XmlElementFactory;
import dev.mybatis.assistant.core.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class MethodQueryAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(@NotNull AnActionEvent e){e.getPresentation().setEnabled(e.getProject()!=null && !DumbService.isDumb(e.getProject()));}
    @Override public void actionPerformed(@NotNull AnActionEvent e){
        var project=e.getProject();PsiElement element=e.getData(CommonDataKeys.PSI_ELEMENT);var editor=e.getData(CommonDataKeys.EDITOR);PsiFile file=e.getData(CommonDataKeys.PSI_FILE);
        if(element==null && editor!=null && file!=null)element=file.findElementAt(editor.getCaretModel().getOffset());
        PsiMethod method=element==null?null:PsiTreeUtil.getParentOfType(element,PsiMethod.class,false);
        if(project==null || method==null || !MapperModel.mapper(method.getContainingClass())){if(project!=null)Messages.showInfoMessage(project,"请把光标放在 Mapper 接口方法上。","MyBatis Assistant");return;}
        if(!MapperModel.statements(method).isEmpty()){Messages.showInfoMessage(project,"该方法已有 XML 语句。","MyBatis Assistant");return;}
        if(MapperModel.mapperRoots(method.getContainingClass()).stream().anyMatch(MapperModel::isLegacy)){Messages.showInfoMessage(project,"方法名 SQL 生成目前使用 MyBatis 3 语法；iBATIS 项目可以使用 XML 语句骨架。","MyBatis Assistant");return;}
        PsiType type=MapperModel.entityType(method.getReturnType());String result=type==null?null:type.getCanonicalText();
        String suggestion=result==null?"users":Schema.snake(result.substring(result.lastIndexOf('.')+1));
        String table=Messages.showInputDialog(project,"数据库表名（当前使用 MySQL 标识符转义）","根据方法名生成 SQL",null,suggestion,null);if(table==null || table.isBlank())return;
        try {
            Map<String,String> bindings=new HashMap<>();ParameterModel model=ParameterModel.of(method,null);model.symbols().forEach((name,s)->bindings.put(name,name));
            String xml=MethodQuery.generate(method.getName(),table,result,bindings,true);
            var roots=MapperModel.xmls(project,method.getContainingClass().getQualifiedName());
            if(roots.isEmpty()){Messages.showInfoMessage(project,xml,"SQL 预览：请先生成 Mapper XML 骨架");return;}
            if(Messages.showOkCancelDialog(project,xml,"预览生成的 SQL","写入 XML","取消",null)!=Messages.OK)return;
            WriteCommandAction.runWriteCommandAction(project,"方法名生成 MyBatis SQL",null,()->roots.getFirst().addSubTag(XmlElementFactory.getInstance(project).createTagFromText(xml),false));
        }catch(IllegalArgumentException ex){Messages.showErrorDialog(project,ex.getMessage(),"MyBatis Assistant");}
    }
}
