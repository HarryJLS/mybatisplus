package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import dev.mybatis.assistant.core.Schema;
import java.util.*;
import java.util.regex.Matcher;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlText;

public final class WrapIfIntention implements IntentionAction {
    @Override public @NotNull String getText(){return "为选中的 SQL 添加 if test";}
    @Override public @NotNull String getFamilyName(){return "MyBatis Assistant";}
    @Override public boolean startInWriteAction(){return true;}
    @Override public boolean isAvailable(@NotNull Project project,Editor editor,PsiFile file){
        TextRange range=range(editor,file);if(range==null)return false;
        String selected=range.substring(file.getText());
        PsiElement element=file.findElementAt(range.getStartOffset());
        return selected!=null && selected.contains("#{") && !selected.contains("<") && !MapperModel.isLegacy(MapperModel.root(element)) && MapperModel.statement(element)!=null;
    }
    @Override public void invoke(@NotNull Project project,Editor editor,PsiFile file){
        TextRange range=range(editor,file);if(range==null)return;String text=range.substring(file.getText());
        try{
            ParameterModel model=ParameterModel.of(null,file.findElementAt(range.getStartOffset()));
            String condition=dev.mybatis.assistant.core.IfTests.condition(text,path->{var symbol=model.resolve(path);return symbol==null?null:symbol.type() instanceof PsiArrayType?"length":symbol.type()!=null && com.intellij.psi.util.InheritanceUtil.isInheritor(symbol.type(),"java.util.List")?"size":null;});
            editor.getDocument().replaceString(range.getStartOffset(),range.getEndOffset(),"<if test=\""+Schema.xml(condition)+"\">\n"+text+"\n</if>");
        }catch(IllegalArgumentException e){com.intellij.openapi.ui.Messages.showErrorDialog(project,e.getMessage(),"添加 if test");return;}
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
    }
    private static TextRange range(Editor editor,PsiFile file){
        if(editor==null)return null;var selection=editor.getSelectionModel();if(selection.hasSelection())return new TextRange(selection.getSelectionStart(),selection.getSelectionEnd());
        int offset=editor.getCaretModel().getOffset();XmlText text=PsiTreeUtil.getParentOfType(file.findElementAt(offset),XmlText.class,false);if(text==null)return null;
        var document=editor.getDocument();int line=document.getLineNumber(offset);int start=Math.max(text.getTextRange().getStartOffset(),document.getLineStartOffset(line)),end=Math.min(text.getTextRange().getEndOffset(),document.getLineEndOffset(line));
        if(end<=start)return null;String sql=document.getText(new TextRange(start,end)).stripLeading();
        return sql.contains("#{") && sql.matches("(?is)^(AND|OR)\\b.*")?new TextRange(start,end):null;
    }
}
