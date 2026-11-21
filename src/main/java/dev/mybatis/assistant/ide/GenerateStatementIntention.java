package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import dev.mybatis.assistant.core.Schema;
import org.jetbrains.annotations.NotNull;
import java.nio.charset.StandardCharsets;

public final class GenerateStatementIntention extends PsiElementBaseIntentionAction {
    @Override public @NotNull String getText(){return "生成 MyBatis XML 语句骨架";}
    @Override public @NotNull String getFamilyName(){return "MyBatis Assistant";}
    @Override public boolean isAvailable(@NotNull Project p,Editor editor,@NotNull PsiElement e){
        if(DumbService.isDumb(p))return false;PsiMethod m=PsiTreeUtil.getParentOfType(e,PsiMethod.class,false);
        return m!=null && MapperModel.mapper(m.getContainingClass()) && !MapperModel.annotated(m) && MapperModel.statements(m).isEmpty();
    }
    @Override public void invoke(@NotNull Project p,Editor editor,@NotNull PsiElement e){PsiMethod m=PsiTreeUtil.getParentOfType(e,PsiMethod.class,false);if(m!=null)generate(m);}
    public static void generate(PsiMethod method){
        PsiClass cls=method.getContainingClass();if(cls==null || cls.getQualifiedName()==null || !MapperModel.statements(method).isEmpty())return;
        String name=method.getName();String tag=name.startsWith("insert") || name.startsWith("add")?"insert":name.startsWith("update")?"update":name.startsWith("delete") || name.startsWith("remove")?"delete":"select";
        var roots=MapperModel.mapperRoots(cls);boolean legacy=!roots.isEmpty() && MapperModel.isLegacy(roots.getFirst());
        PsiType result=MapperModel.entityType(method.getReturnType());String type=result==null?"map":result.getCanonicalText();
        String statement="<"+tag+" id=\""+name+"\""+(tag.equals("select")?" "+(legacy?"resultClass":"resultType")+"=\""+Schema.xml(type)+"\"":"")+">\n<!-- TODO: 请填写 SQL -->\n</"+tag+">";
        if(!roots.isEmpty()) {XmlTag root=roots.getFirst();XmlTag child=XmlElementFactory.getInstance(method.getProject()).createTagFromText(statement);CodeStyleManager.getInstance(method.getProject()).reformat(root.addSubTag(child,false));return;}
        try {
            var module=com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(method);VirtualFile base=null;
            if(module!=null){var contents=com.intellij.openapi.roots.ModuleRootManager.getInstance(module).getContentRoots();if(contents.length>0)base=contents[0];}
            if(base==null)base=method.getContainingFile().getVirtualFile().getParent();
            VirtualFile dir=VfsUtil.createDirectoryIfMissing(base.getPath()+"/src/main/resources/mapper");if(dir==null)return;
            String filename=cls.getName()+".xml";
            if(dir.findChild(filename)!=null)throw new IllegalStateException("已有同名 XML，但 namespace 不匹配，请先检查该文件");
            VirtualFile file=dir.createChildData(GenerateStatementIntention.class,filename);
            file.setBinaryContent(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n<mapper namespace=\""+cls.getQualifiedName()+"\">\n"+statement+"\n</mapper>\n").getBytes(StandardCharsets.UTF_8));
        } catch(java.io.IOException ex){throw new com.intellij.util.IncorrectOperationException(ex.getMessage(),ex);}
    }
}
