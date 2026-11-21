package dev.mybatis.assistant.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class AssistantToolWindow implements ToolWindowFactory {
    @Override public void createToolWindowContent(@NotNull Project project,@NotNull ToolWindow window){
        AssistantPanel panel=new AssistantPanel(project);window.getContentManager().addContent(ContentFactory.getInstance().createContent(panel,"",false));
    }
    public static void show(Project project,int tab){
        ToolWindow window=ToolWindowManager.getInstance(project).getToolWindow("MyBatis Assistant");if(window==null)return;
        window.show(()->{var content=window.getContentManager().getContent(0);if(content!=null && content.getComponent() instanceof AssistantPanel panel)panel.select(tab);});
    }
}
