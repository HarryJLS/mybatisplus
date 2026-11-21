package dev.mybatis.assistant.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

public final class LegacyNavigateAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(@NotNull AnActionEvent e){e.getPresentation().setEnabled(e.getProject()!=null && !DumbService.isDumb(e.getProject()));}
    @Override public void actionPerformed(@NotNull AnActionEvent e){
        var project=e.getProject();if(project==null)return;PsiElement element=e.getData(CommonDataKeys.PSI_ELEMENT);
        var editor=e.getData(CommonDataKeys.EDITOR);var file=e.getData(CommonDataKeys.PSI_FILE);
        if(element==null && editor!=null && file!=null)element=file.findElementAt(editor.getCaretModel().getOffset());
        PsiMethod method=element==null?null:PsiTreeUtil.getParentOfType(element,PsiMethod.class,false);
        if(method==null){Messages.showInfoMessage(project,"请将光标放在 DAO 接口或实现方法上。","iBATIS SQL 跳转");return;}
        LegacyNavigation.navigate(method);
    }
}
