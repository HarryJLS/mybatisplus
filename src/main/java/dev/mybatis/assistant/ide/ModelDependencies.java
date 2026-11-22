package dev.mybatis.assistant.ide;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.indexing.FileBasedIndex;

/** Type models need Java/Kotlin edits, rather than every SQL text edit in the project. */
final class ModelDependencies {
    private ModelDependencies() {}
    static ModificationTracker types(Project project) {
        return PsiModificationTracker.getInstance(project).forLanguages(language ->
            language.isKindOf(JavaLanguage.INSTANCE) || "kotlin".equals(language.getID()));
    }
    static ModificationTracker namespaces(Project project) {
        return () -> FileBasedIndex.getInstance().getIndexModificationStamp(MapperNamespaceIndex.NAME,project);
    }
}
