package dev.mybatis.assistant.ide;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.RenameHandler;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Rename the binding string independently of the Java identifier; standard Rename remains intact. */
public final class ParamRenameHandler implements RenameHandler {
    @Override public boolean isAvailableOnDataContext(@NotNull DataContext context){return target(context)!=null;}
    @Override public void invoke(@NotNull Project project,Editor editor,PsiFile file,@NotNull DataContext context){invoke(project,context);}
    @Override public void invoke(@NotNull Project project,PsiElement @NotNull [] elements,@NotNull DataContext context){invoke(project,context);}
    private static void invoke(Project project,DataContext context){
        PsiParameter parameter=target(context);if(parameter==null)return;
        String name=Messages.showInputDialog(project,"新参数绑定名称（同步 XML 和注解 SQL）","重命名 @Param",null,ParameterModel.paramName(parameter),null);
        if(name==null)return;
        if(!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")){Messages.showErrorDialog(project,"请输入有效的参数名称","MyBatis Assistant");return;}
        try {rename(parameter,name);}catch(IllegalArgumentException e){Messages.showErrorDialog(project,e.getMessage(),"MyBatis Assistant");}
    }
    private static PsiParameter target(DataContext context){
        Project project=CommonDataKeys.PROJECT.getData(context);if(project==null || DumbService.isDumb(project))return null;
        PsiElement element=CommonDataKeys.PSI_ELEMENT.getData(context);Editor editor=CommonDataKeys.EDITOR.getData(context);PsiFile file=CommonDataKeys.PSI_FILE.getData(context);
        if(editor!=null && file!=null){
            int offset=editor.getCaretModel().getOffset();PsiReference reference=file.findReferenceAt(offset);
            if(reference!=null && reference.resolve() instanceof PsiParameter p && ParameterModel.paramName(p)!=null)return p;
            element=file.findElementAt(Math.min(offset,Math.max(0,file.getTextLength()-1)));
        }
        PsiLiteralExpression literal=element==null?null:PsiTreeUtil.getParentOfType(element,PsiLiteralExpression.class,false);
        PsiAnnotation annotation=literal==null?null:PsiTreeUtil.getParentOfType(literal,PsiAnnotation.class);
        if(annotation!=null && "org.apache.ibatis.annotations.Param".equals(annotation.getQualifiedName()))return PsiTreeUtil.getParentOfType(annotation,PsiParameter.class);
        return null;
    }
    public static void rename(PsiParameter parameter,String name){
        if(!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*"))throw new IllegalArgumentException("无效的参数绑定名称");
        String old=ParameterModel.paramName(parameter);if(old==null || old.equals(name))return;
        if(!(parameter.getDeclarationScope() instanceof PsiMethod method))return;
        if(AnnotationSql.scripted(method))throw new IllegalArgumentException("script 注解方法的参数重构尚未支持；请同时检查脚本中的全局与局部变量");
        if(!AnnotationSql.editableBinding(method))throw new IllegalArgumentException("注解 SQL 来自外部常量或字符串表达式，暂不自动重构参数绑定");
        PsiElement source=parameter.getNavigationElement();
        if(source.getContainingFile()!=null && "kt".equals(source.getContainingFile().getVirtualFile()==null?null:source.getContainingFile().getVirtualFile().getExtension()) && MapperLanguageSupport.EP.getExtensionList().stream().noneMatch(s->s.canRenameBinding(parameter)))throw new IllegalArgumentException("此 Kotlin 参数绑定未支持自动重构，未修改文件");
        for(PsiParameter other:method.getParameterList().getParameters())if(other!=parameter && (name.equals(ParameterModel.paramName(other)) || name.equals(other.getName()) && ParameterModel.paramName(other)==null))throw new IllegalArgumentException("参数绑定名称已存在: "+name);
        Map<PsiElement,List<PsiReference>> changes=new LinkedHashMap<>();
        for(PsiReference ref:ReferencesSearch.search(parameter)){
            PsiElement host=ref.getElement();
            if(host instanceof com.intellij.psi.xml.XmlAttributeValue || host instanceof com.intellij.psi.xml.XmlText || host instanceof PsiLiteralExpression && AnnotationSql.method(host)!=null)changes.computeIfAbsent(host,key->new ArrayList<>()).add(ref);
        }
        if(changes.values().stream().mapToInt(List::size).sum()>10000)throw new IllegalArgumentException("参数引用超过 10000 个，请缩小重构范围");
        WriteCommandAction.runWriteCommandAction(parameter.getProject(),"重命名 MyBatis 参数绑定",null,()->{
            for(var entry:changes.entrySet()){
                entry.getValue().sort(Comparator.comparingInt((PsiReference r)->r.getRangeInElement().getStartOffset()).reversed());
                PsiElement host=entry.getKey();Set<com.intellij.openapi.util.TextRange> seen=new HashSet<>();
                for(PsiReference ref:entry.getValue())if(seen.add(ref.getRangeInElement()))host=ElementManipulators.handleContentChange(host,ref.getRangeInElement(),name);
            }
            PsiAnnotation annotation=parameter.getAnnotation("org.apache.ibatis.annotations.Param");
            boolean handled=false;for(MapperLanguageSupport support:MapperLanguageSupport.EP.getExtensionList())if(support.renameBinding(parameter,name)){handled=true;break;}
            if(!handled && annotation!=null)annotation.setDeclaredAttributeValue("value",JavaPsiFacade.getElementFactory(parameter.getProject()).createExpressionFromText("\""+name+"\"",parameter));
        });
    }
}
