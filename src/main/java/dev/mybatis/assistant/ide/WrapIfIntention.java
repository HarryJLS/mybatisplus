package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import dev.mybatis.assistant.core.Schema;
import java.util.*;
import java.util.regex.Matcher;
import org.jetbrains.annotations.NotNull;

public final class WrapIfIntention implements IntentionAction {
    @Override public @NotNull String getText(){return "为选中的 SQL 添加 if test";}
    @Override public @NotNull String getFamilyName(){return "MyBatis Assistant";}
    @Override public boolean startInWriteAction(){return true;}
    @Override public boolean isAvailable(@NotNull Project project,Editor editor,PsiFile file){
        if(editor==null || !editor.getSelectionModel().hasSelection())return false;
        String selected=editor.getSelectionModel().getSelectedText();
        PsiElement element=file.findElementAt(editor.getSelectionModel().getSelectionStart());
        return selected!=null && selected.contains("#{") && !selected.contains("<") && !MapperModel.isLegacy(MapperModel.root(element)) && MapperModel.statement(element)!=null;
    }
    @Override public void invoke(@NotNull Project project,Editor editor,PsiFile file){
        var selection=editor.getSelectionModel();String text=selection.getSelectedText();if(text==null)return;
        Set<String> conditions=new LinkedHashSet<>();Matcher m=MapperReferences.PLACEHOLDER.matcher(text);
        while(m.find()){
            String path=m.group(1);String[] parts=path.split("\\.");String prefix="";
            for(String part:parts){prefix=prefix.isEmpty()?part:prefix+"."+part;conditions.add(prefix+" != null");}
        }
        if(conditions.isEmpty())return;
        editor.getDocument().replaceString(selection.getSelectionStart(),selection.getSelectionEnd(),"<if test=\""+Schema.xml(String.join(" and ",conditions))+"\">\n"+text+"\n</if>");
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
    }
}
