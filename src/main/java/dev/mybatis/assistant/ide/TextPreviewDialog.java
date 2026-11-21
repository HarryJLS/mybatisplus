package dev.mybatis.assistant.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.*;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

final class TextPreviewDialog extends DialogWrapper {
    private final String text;
    TextPreviewDialog(Project project,String title,String text){super(project,false);this.text=text;setTitle(title);setOKButtonText("复制并关闭");init();}
    @Override protected JComponent createCenterPanel(){JBTextArea area=new JBTextArea(text);area.setEditable(false);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));JBScrollPane pane=new JBScrollPane(area);pane.setPreferredSize(new Dimension(760,460));return pane;}
    @Override protected void doOKAction(){Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null);super.doOKAction();}
}
