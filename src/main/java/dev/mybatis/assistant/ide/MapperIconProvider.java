package dev.mybatis.assistant.ide;

import com.intellij.ide.IconProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlFile;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Supplies the same icon to PSI file and class presentations (tree, tabs and navigation). */
public final class MapperIconProvider extends IconProvider implements DumbAware {
    @Override public @Nullable Icon getIcon(@NotNull PsiElement element, int flags) {
        if (element instanceof XmlFile) return MapperModel.root(element) == null ? null : AssistantIcons.XML_MAPPER;
        if (DumbService.isDumb(element.getProject())) return null;
        if (element instanceof PsiClass cls) return MapperModel.mapper(cls) ? AssistantIcons.JAVA_MAPPER : null;
        if (element instanceof PsiJavaFile file) {
            String name = file.getVirtualFile() == null ? file.getName() : file.getVirtualFile().getName();
            String base = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
            for (PsiClass cls : file.getClasses()) {
                if (base.equals(cls.getName())) return MapperModel.mapper(cls) ? AssistantIcons.JAVA_MAPPER : null;
            }
        }
        return null;
    }
}
