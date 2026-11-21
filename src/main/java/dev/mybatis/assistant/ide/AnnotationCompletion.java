package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class AnnotationCompletion extends CompletionContributor {
    public AnnotationCompletion(){extend(CompletionType.BASIC,PlatformPatterns.psiElement(),new CompletionProvider<>(){
        @Override protected void addCompletions(@NotNull CompletionParameters p,@NotNull ProcessingContext c,@NotNull CompletionResultSet result){
            if(!AssistantSettings.get(p.getPosition().getProject()).completion)return;
            PsiLiteralExpression literal=PsiTreeUtil.getParentOfType(p.getPosition(),PsiLiteralExpression.class,false);if(literal==null || AnnotationSql.method(literal)==null)return;
            if(AnnotationSql.scripted(AnnotationSql.method(literal)))return;
            int end=Math.min(literal.getTextLength(),p.getOffset()-literal.getTextRange().getStartOffset());String before=literal.getText().substring(0,Math.max(0,end));
            int open=Math.max(before.lastIndexOf("#{"),before.lastIndexOf("${"));
            if(open>=0 && before.indexOf('}',open)<0){
                String path=before.substring(open+2).stripLeading();int dot=path.lastIndexOf('.');
                var target=result.withPrefixMatcher(dot<0?path:path.substring(dot+1));
                ParameterModel.of(AnnotationSql.method(literal),null).complete(path).forEach((name,symbol)->target.addElement(LookupElementBuilder.create(name).withTypeText(symbol.type()==null?"参数":symbol.type().getPresentableText())));
            }else for(String keyword:List.of("SELECT","FROM","WHERE","AND","OR","JOIN","ON","ORDER BY","INSERT INTO","VALUES","UPDATE","SET","DELETE FROM"))result.addElement(LookupElementBuilder.create(keyword).withTypeText("SQL"));
        }
    });}
}
