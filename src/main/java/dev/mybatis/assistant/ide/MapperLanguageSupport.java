package dev.mybatis.assistant.ide;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.psi.*;

public interface MapperLanguageSupport {
    ExtensionPointName<MapperLanguageSupport> EP=ExtensionPointName.create("dev.mybatis.assistant.languageSupport");
    PsiMethod method(PsiElement element);
    default PsiElement navigation(PsiElement element){return element.getNavigationElement();}
    default String annotationSql(PsiMethod method){return null;}
    default boolean renameBinding(PsiParameter parameter,String name){return false;}
    default boolean canRenameBinding(PsiParameter parameter){return false;}
    default java.util.List<PsiMethod> methods(PsiFile file){return java.util.List.of();}
    static PsiMethod findMethod(PsiElement element){
        PsiMethod java=com.intellij.psi.util.PsiTreeUtil.getParentOfType(element,PsiMethod.class,false);if(java!=null)return java;
        for(MapperLanguageSupport support:EP.getExtensionList()){PsiMethod method=support.method(element);if(method!=null)return method;}return null;
    }
}
