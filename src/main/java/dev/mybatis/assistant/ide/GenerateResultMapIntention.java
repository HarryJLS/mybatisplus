package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.ui.*;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.ui.components.*;
import java.awt.*;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public final class GenerateResultMapIntention extends PsiElementBaseIntentionAction {
    @Override public @NotNull String getText(){return "从 SELECT 生成 resultMap（预览）";}
    @Override public @NotNull String getFamilyName(){return "MyBatis Assistant";}
    @Override public boolean startInWriteAction(){return false;}
    @Override public boolean isAvailable(@NotNull Project project,Editor editor,@NotNull PsiElement element){XmlTag tag=MapperModel.statement(element);return !DumbService.isDumb(project) && tag!=null && tag.getName().equals("select") && !MapperModel.isLegacy(MapperModel.root(tag));}
    @Override public void invoke(@NotNull Project project,Editor editor,@NotNull PsiElement element){
        XmlTag statement=MapperModel.statement(element);if(statement!=null)generate(statement);
    }
    public static void generate(XmlTag statement){
        Project project=statement.getProject();if(!statement.isValid() || project.isDisposed() || DumbService.isDumb(project) || !statement.getName().equals("select") || MapperModel.isLegacy(MapperModel.root(statement)))return;
        var pointer=SmartPointerManager.getInstance(project).createSmartPsiElementPointer(statement);
        new Task.Backgroundable(project,"解析 SELECT 与实体映射",true){ResultMapEdits.Plan plan;String error;
            @Override public void run(@NotNull ProgressIndicator indicator){try{plan=ReadAction.compute(()->{XmlTag current=pointer.getElement();if(current==null)throw new IllegalArgumentException("SELECT 已删除");return ResultMapEdits.generate(current);});}catch(ProcessCanceledException e){throw e;}catch(IllegalArgumentException e){error=e.getMessage();}}
            @Override public void onSuccess(){if(project.isDisposed())return;if(error!=null)Messages.showErrorDialog(project,error,"生成 resultMap");else preview(project,pointer,plan);}
        }.queue();
    }
    private static void preview(Project project,SmartPsiElementPointer<XmlTag> pointer,ResultMapEdits.Plan plan){
        XmlTag statement=pointer.getElement();if(statement==null)return;
        try{
            JBTextArea code=new JBTextArea(plan.xml());code.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
            DialogWrapper preview=new DialogWrapper(project,false){ {setTitle("生成 resultMap：确认列与属性映射");setOKButtonText("写入并关联 SELECT");init();} @Override protected JComponent createCenterPanel(){JPanel panel=new JPanel(new BorderLayout());panel.add(new JLabel(plan.unmatched().isEmpty()?"已匹配所有输出列，可编辑后写入。":"未匹配列："+String.join(", ",plan.unmatched())+"；可在预览中补充映射。"),BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(code);scroll.setPreferredSize(new Dimension(780,460));panel.add(scroll);return panel;} };
            if(!preview.showAndGet())return;
            if(com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount()!=plan.psiStamp())throw new IllegalArgumentException("Mapper 或实体已变更，请重新生成 resultMap");
            XmlTag generated=XmlElementFactory.getInstance(project).createTagFromText(code.getText());String id=generated.getAttributeValue("id");
            if(!generated.getName().equals("resultMap") || id==null || id.isBlank())throw new IllegalArgumentException("预览必须是包含 id 的 resultMap");
            WriteCommandAction.runWriteCommandAction(project,"生成 SELECT resultMap",null,()->{
                if(!statement.isValid() || MapperModel.definition(statement,id,"resultMap")!=null)throw new IllegalArgumentException("语句已变更或 resultMap id 已存在，请重新生成");
                CodeStyleManager.getInstance(project).reformat(MapperModel.root(statement).addSubTag(generated,false));statement.setAttribute("resultMap",id);statement.setAttribute("resultType",null);
            });
        }catch(IllegalArgumentException e){Messages.showErrorDialog(project,e.getMessage(),"生成 resultMap");}
    }
}
