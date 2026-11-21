package dev.mybatis.assistant.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.refactoring.rename.RenameHandler;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class ScopedVariableRenameHandler implements RenameHandler {
    @Override public boolean isAvailableOnDataContext(@NotNull DataContext context){return target(context)!=null;}
    @Override public void invoke(@NotNull Project project,Editor editor,PsiFile file,@NotNull DataContext context){invoke(project,context);}
    @Override public void invoke(@NotNull Project project,PsiElement @NotNull [] elements,@NotNull DataContext context){invoke(project,context);}
    private static void invoke(Project project,DataContext context){
        XmlAttributeValue value=target(context);if(value==null)return;
        String name=Messages.showInputDialog(project,"新的局部变量名称（仅更新此作用域的引用）","重命名 MyBatis 局部变量",null,value.getValue(),null);
        if(name!=null)try{rename(value,name);}catch(IllegalArgumentException e){Messages.showErrorDialog(project,e.getMessage(),"MyBatis Assistant");}
    }
    private static boolean valid(PsiElement element){
        if(!(element instanceof XmlAttributeValue value) || !(value.getParent() instanceof XmlAttribute a))return false;
        return a.getParent().getName().equals("bind") && a.getName().equals("name") || a.getParent().getName().equals("foreach") && Set.of("item","index").contains(a.getName());
    }
    private static XmlAttributeValue target(DataContext context){
        Project project=CommonDataKeys.PROJECT.getData(context);if(project==null || DumbService.isDumb(project))return null;
        PsiFile file=CommonDataKeys.PSI_FILE.getData(context);Editor editor=CommonDataKeys.EDITOR.getData(context);PsiElement element=CommonDataKeys.PSI_ELEMENT.getData(context);
        if(editor!=null && file!=null){int offset=editor.getCaretModel().getOffset();PsiReference ref=file.findReferenceAt(offset);if(ref!=null && valid(ref.resolve()))return (XmlAttributeValue)ref.resolve();element=file.findElementAt(Math.min(offset,Math.max(0,file.getTextLength()-1)));}
        XmlAttributeValue value=element==null?null:PsiTreeUtil.getParentOfType(element,XmlAttributeValue.class,false);return valid(value)?value:null;
    }
    public static void rename(XmlAttributeValue value,String name){
        if(!valid(value) || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*"))throw new IllegalArgumentException("无效的局部变量名称");
        XmlAttribute attribute=(XmlAttribute)value.getParent();XmlTag statement=MapperModel.statement(value);if(statement==null)return;
        if(value.getValue().equals(name))return;
        ParameterModel model=ParameterModel.of(MapperModel.method(statement),attribute.getParent());
        var collision=model.resolve(name);if(collision!=null && collision.declaration()!=value)throw new IllegalArgumentException("名称会覆盖已有参数或局部变量: "+name);
        Map<PsiElement,List<PsiReference>> changes=new LinkedHashMap<>();
        Collection<PsiElement> hosts=new ArrayList<>();hosts.addAll(PsiTreeUtil.findChildrenOfType(statement,XmlAttributeValue.class));hosts.addAll(PsiTreeUtil.findChildrenOfType(statement,XmlText.class));
        if(hosts.size()>4096)throw new IllegalArgumentException("语句超过局部变量重构上限");
        for(PsiElement host:hosts){PsiReference[] refs=host instanceof XmlText text?MapperReferences.text(text):host.getReferences();for(PsiReference ref:refs)if(ref.isReferenceTo(value))changes.computeIfAbsent(host,key->new ArrayList<>()).add(ref);}
        WriteCommandAction.runWriteCommandAction(value.getProject(),"重命名 MyBatis 局部变量",null,()->{
            for(var entry:changes.entrySet()){
                PsiElement host=entry.getKey();entry.getValue().sort(Comparator.comparingInt((PsiReference ref)->ref.getRangeInElement().getStartOffset()).reversed());
                for(PsiReference ref:entry.getValue())host=ElementManipulators.handleContentChange(host,ref.getRangeInElement(),name);
            }attribute.setValue(name);
        });
    }
}
