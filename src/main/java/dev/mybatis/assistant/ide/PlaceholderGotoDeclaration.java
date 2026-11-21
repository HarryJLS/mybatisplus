package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlText;

/** XmlText does not expose contributed references through getReferences() in current IDEA. */
public final class PlaceholderGotoDeclaration implements GotoDeclarationHandler {
    @Override public PsiElement[] getGotoDeclarationTargets(PsiElement source,int offset,Editor editor){
        if(source==null)return null;XmlText text=PsiTreeUtil.getParentOfType(source,XmlText.class,false);if(text==null)return null;
        int local=offset-text.getTextRange().getStartOffset();
        for(PsiReference ref:MapperReferences.text(text))if(ref.getRangeInElement().containsOffset(local)){
            PsiElement resolved=ref.resolve();if(resolved!=null)return new PsiElement[]{resolved.getNavigationElement()};
        }
        return null;
    }
}
