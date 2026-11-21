package dev.mybatis.assistant.ide;
import com.intellij.openapi.actionSystem.*;
import org.jetbrains.annotations.NotNull;
public final class LogSqlAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e){if(e.getProject()!=null)AssistantToolWindow.show(e.getProject(),1);}
    @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(@NotNull AnActionEvent e){e.getPresentation().setEnabled(e.getProject()!=null);}
}
