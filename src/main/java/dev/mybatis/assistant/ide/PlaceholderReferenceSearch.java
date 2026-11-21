package dev.mybatis.assistant.ide;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlText;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.*;
import org.jetbrains.annotations.NotNull;

/** Explicit refactoring uses IDEA's word index; it never walks every project file. */
public final class PlaceholderReferenceSearch implements QueryExecutor<PsiReference,ReferencesSearch.SearchParameters> {
    @Override public boolean execute(@NotNull ReferencesSearch.SearchParameters parameters,@NotNull Processor<? super PsiReference> consumer){
        PsiElement target=parameters.getElementToSearch();
        if(!(target instanceof PsiField || target instanceof PsiParameter))return true;
        String name=target instanceof PsiParameter p && ParameterModel.paramName(p)!=null?ParameterModel.paramName(p):((PsiNamedElement)target).getName();if(name==null || name.isEmpty())return true;
        SearchScope xmlScope=GlobalSearchScope.projectScope(target.getProject());
        SearchScope scope=parameters.getScopeDeterminedByUser().intersectWith(xmlScope);
        return PsiSearchHelper.getInstance(target.getProject()).processElementsWithWord((element,offset)->{
            PsiElement host=PsiTreeUtil.getParentOfType(element,XmlText.class,false);
            if(host==null)host=PsiTreeUtil.getParentOfType(element,XmlAttributeValue.class,false);
            if(host==null)host=PsiTreeUtil.getParentOfType(element,PsiLiteralExpression.class,false);
            if(host==null)return true;
            int local=element.getTextRange().getStartOffset()+offset-host.getTextRange().getStartOffset();
            PsiReference[] references=host instanceof XmlText text?MapperReferences.text(text):host instanceof PsiLiteralExpression literal?AnnotationSql.references(literal):host.getReferences();
            for(PsiReference ref:references)if(ref.getRangeInElement().containsOffset(local) && ref.isReferenceTo(target))if(!consumer.process(ref))return false;
            return true;
        },scope,name,UsageSearchContext.ANY,true);
    }
}
