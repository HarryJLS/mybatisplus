package dev.mybatis.assistant.ide;
import com.intellij.openapi.actionSystem.*;
public final class DatabaseExecuteAction extends AnAction {
    @Override public void actionPerformed(AnActionEvent event){if(event.getProject()!=null)AssistantToolWindow.show(event.getProject(),3);}
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(AnActionEvent event){event.getPresentation().setEnabled(event.getProject()!=null);}
}
