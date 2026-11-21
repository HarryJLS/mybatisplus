package dev.mybatis.assistant.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.refactoring.rename.RenameHandler;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Explicit XML id refactoring uses the platform word index, preserving namespace prefixes. */
public final class MappingIdRenameHandler implements RenameHandler {
    @Override public boolean isAvailableOnDataContext(@NotNull DataContext context){return target(context)!=null;}
    @Override public void invoke(@NotNull Project project,Editor editor,PsiFile file,@NotNull DataContext context){invoke(project,context);}
    @Override public void invoke(@NotNull Project project,PsiElement @NotNull [] elements,@NotNull DataContext context){invoke(project,context);}
    private static void invoke(Project project,DataContext context){
        XmlAttributeValue id=target(context);if(id==null)return;
        String old=id.getValue();String name=Messages.showInputDialog(project,"新的映射 id（同步 refid/resultMap/extends/parameterMap 引用）","重命名映射 id",null,old.substring(old.lastIndexOf('.')+1),null);
        if(name==null)return;try{rename(id,name);}catch(IllegalArgumentException e){Messages.showErrorDialog(project,e.getMessage(),"MyBatis Assistant");}
    }
    private static XmlAttributeValue target(DataContext context){
        Project project=CommonDataKeys.PROJECT.getData(context);if(project==null || DumbService.isDumb(project))return null;
        Editor editor=CommonDataKeys.EDITOR.getData(context);PsiFile file=CommonDataKeys.PSI_FILE.getData(context);PsiElement element=CommonDataKeys.PSI_ELEMENT.getData(context);
        if(editor!=null && file!=null){
            int offset=editor.getCaretModel().getOffset();PsiReference ref=file.findReferenceAt(offset);if(ref!=null && valid(ref.resolve()))return (XmlAttributeValue)ref.resolve();
            element=file.findElementAt(Math.min(offset,Math.max(0,file.getTextLength()-1)));
        }
        XmlAttributeValue value=element==null?null:PsiTreeUtil.getParentOfType(element,XmlAttributeValue.class,false);return valid(value)?value:null;
    }
    private static boolean valid(PsiElement element){
        return element instanceof XmlAttributeValue value && value.getParent() instanceof XmlAttribute a && a.getName().equals("id") && Set.of("sql","resultMap","parameterMap").contains(a.getParent().getName()) && MapperModel.root(value)!=null;
    }
    public static void rename(XmlAttributeValue id,String name){
        if(!valid(id) || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*"))throw new IllegalArgumentException("无效的映射 id");
        XmlAttribute attribute=(XmlAttribute)id.getParent();XmlTag tag=attribute.getParent(),root=MapperModel.root(tag);String old=id.getValue(),local=old.substring(old.lastIndexOf('.')+1);
        if(local.equals(name))return;
        String replacement=old.substring(0,old.lastIndexOf('.')+1)+name;
        for(XmlTag sibling:root.getSubTags())if(sibling!=tag && sibling.getName().equals(tag.getName()) && replacement.equals(sibling.getAttributeValue("id")))throw new IllegalArgumentException("同类型映射 id 已存在: "+replacement);
        Map<PsiElement,List<PsiReference>> changes=new LinkedHashMap<>();
        PsiSearchHelper.getInstance(id.getProject()).processElementsWithWord((element,offset)->{
            XmlAttributeValue host=PsiTreeUtil.getParentOfType(element,XmlAttributeValue.class,false);if(host==null)return true;
            for(PsiReference ref:host.getReferences())if(ref.isReferenceTo(id))changes.computeIfAbsent(host,key->new ArrayList<>()).add(ref);
            return changes.size()<10000;
        },GlobalSearchScope.projectScope(id.getProject()),local,UsageSearchContext.ANY,true);
        if(changes.size()>=10000)throw new IllegalArgumentException("映射引用超过上限");
        WriteCommandAction.runWriteCommandAction(id.getProject(),"重命名 MyBatis 映射 id",null,()->{
            for(var entry:changes.entrySet()){
                PsiElement host=entry.getKey();entry.getValue().sort(Comparator.comparingInt((PsiReference ref)->ref.getRangeInElement().getStartOffset()).reversed());Set<com.intellij.openapi.util.TextRange> seen=new HashSet<>();
                for(PsiReference ref:entry.getValue())if(seen.add(ref.getRangeInElement()))host=ElementManipulators.handleContentChange(host,ref.getRangeInElement(),name);
            }
            attribute.setValue(replacement);
        });
    }
}
