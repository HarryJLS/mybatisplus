package dev.mybatis.assistant.ide;

import com.intellij.formatting.InjectedFormattingOptionsProvider;
import com.intellij.psi.PsiFile;

/** Synthetic SQL contexts must not rewrite XML tags, conditions or binding delimiters. */
public final class MapperSqlFormatting implements InjectedFormattingOptionsProvider {
    @Override public Boolean shouldDelegateToTopLevel(PsiFile file){return Boolean.TRUE.equals(file.getUserData(SqlLanguageInjector.XML_SQL))?Boolean.TRUE:null;}
}
