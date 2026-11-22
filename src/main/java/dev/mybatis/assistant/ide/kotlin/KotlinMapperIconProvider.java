package dev.mybatis.assistant.ide.kotlin;

import com.intellij.ide.IconProvider;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import dev.mybatis.assistant.ide.*;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.psi.*;

/** Loaded only with the optional Kotlin plugin. */
public final class KotlinMapperIconProvider extends IconProvider {
    @Override public @Nullable Icon getIcon(@NotNull PsiElement element, int flags) {
        if (DumbService.isDumb(element.getProject())) return null;
        if (element instanceof KtClass cls) return icon(cls);
        if (element instanceof KtFile file) {
            for (KtDeclaration declaration : file.getDeclarations()) {
                if (declaration instanceof KtClass cls && icon(cls) != null) return AssistantIcons.JAVA_MAPPER;
            }
        }
        return null;
    }

    private static Icon icon(KtClass cls) {
        if (!cls.isInterface()) return null;
        PsiClass light = LightClassUtilsKt.toLightClass(cls);
        return MapperModel.mapper(light) ? AssistantIcons.JAVA_MAPPER : null;
    }
}
