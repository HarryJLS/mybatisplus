package dev.mybatis.assistant.ide.kotlin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import dev.mybatis.assistant.ide.MapperLanguageSupport;
import java.util.*;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

public final class KotlinMapperSupport implements MapperLanguageSupport {
    @Override public List<PsiMethod> methods(PsiFile file){
        List<PsiMethod> result=new ArrayList<>();for(KtNamedFunction function:PsiTreeUtil.findChildrenOfType(file,KtNamedFunction.class)){if(result.size()>=512)break;PsiMethod method=method(function);if(method!=null)result.add(method);}return result;
    }
    @Override public PsiMethod method(PsiElement element){
        KtNamedFunction function=PsiTreeUtil.getParentOfType(element,KtNamedFunction.class,false);
        return function==null || function.hasModifier(KtTokens.SUSPEND_KEYWORD)?null:LightClassUtilsKt.getRepresentativeLightMethod(function);
    }
    @Override public PsiElement navigation(PsiElement element){return LightClassUtilsKt.getUnwrapped(element);}
    @Override public String annotationSql(PsiMethod method){
        if(!(LightClassUtilsKt.getUnwrapped(method) instanceof KtNamedFunction function))return null;
        for(KtAnnotationEntry annotation:function.getAnnotationEntries()){
            String shortName=annotation.getShortName()==null?"":annotation.getShortName().asString();
            if(!Set.of("Select","Insert","Update","Delete").contains(shortName) || !method.hasAnnotation("org.apache.ibatis.annotations."+shortName))continue;
            List<String> values=new ArrayList<>();
            for(var argument:annotation.getValueArguments()){
                KtExpression expression=argument.getArgumentExpression();
                KtExpression[] elements=expression instanceof KtCollectionLiteralExpression array?array.getInnerExpressions().toArray(KtExpression[]::new):new KtExpression[]{expression};
                for(KtExpression item:elements){String value=constant(item);if(value==null)return null;values.add(value);}
            }return String.join(" ",values);
        }return null;
    }
    static String constant(KtExpression expression){
        if(!(expression instanceof KtStringTemplateExpression string))return null;StringBuilder value=new StringBuilder();
        for(KtStringTemplateEntry entry:string.getEntries()){
            if(entry instanceof KtStringTemplateEntryWithExpression)return null;
            value.append(entry instanceof KtEscapeStringTemplateEntry escape?escape.getUnescapedValue():entry.getText());
        }return value.toString();
    }
    @Override public boolean renameBinding(PsiParameter parameter,String name){
        KtStringTemplateExpression literal=bindingLiteral(parameter);if(literal==null)return false;ElementManipulators.handleContentChange(literal,name);return true;
    }
    @Override public boolean canRenameBinding(PsiParameter parameter){return bindingLiteral(parameter)!=null;}
    private static KtStringTemplateExpression bindingLiteral(PsiParameter parameter){
        if(!(LightClassUtilsKt.getUnwrapped(parameter) instanceof KtParameter original))return null;
        for(KtAnnotationEntry annotation:original.getAnnotationEntries()){
            PsiAnnotation light=LightClassUtilsKt.toLightAnnotation(annotation);
            if(light==null || !"org.apache.ibatis.annotations.Param".equals(light.getQualifiedName()))continue;
            if(annotation.getValueArguments().size()==1 && annotation.getValueArguments().getFirst().getArgumentExpression() instanceof KtStringTemplateExpression literal && constant(literal)!=null)return literal;
        }return null;
    }
}
