package dev.mybatis.assistant.ide;

import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.*;
import com.intellij.util.indexing.FileBasedIndex;
import java.util.*;
import org.apache.ibatis.type.TypeAliasRegistry;

final class TypeAliases {
    private TypeAliases() {}
    private static final Key<CachedValue<Map<String,String>>> CACHE = Key.create("mybatis.assistant.aliases");
    // Strings only: no project classes or PSI are retained in a static collection.
    private static final Map<String,String> BUILTINS;
    static {
        Map<String,String> values = new TreeMap<>();
        new TypeAliasRegistry().getTypeAliases().forEach((alias,type) -> values.put(alias, type.getCanonicalName()));
        BUILTINS = Map.copyOf(values);
    }
    static Map<String,String> names(PsiElement owner) {
        if (DumbService.isDumb(owner.getProject())) return BUILTINS;
        Project project=owner.getProject();
        Module module=ModuleUtilCore.findModuleForPsiElement(owner);
        // A module/project owns this string-only cache; the provider retains no caller PSI.
        return CachedValuesManager.getManager(project).getCachedValue(module==null?project:module, CACHE, () ->
            CachedValueProvider.Result.create(load(project,module), ModelDependencies.types(project),
                ProjectRootModificationTracker.getInstance(project), project.getService(AssistantSettings.class),
                (com.intellij.openapi.util.ModificationTracker) () -> FileBasedIndex.getInstance().getIndexModificationStamp(TypeAliasIndex.NAME, project)), false);
    }
    static String resolve(PsiElement context, String alias) {
        return alias == null ? null : names(context).get(alias.toLowerCase(Locale.ROOT));
    }
    private static Map<String,String> load(Project project,Module module) {
        Map<String,Set<String>> candidates = new TreeMap<>();
        BUILTINS.forEach((key,value) -> add(candidates,key,value));
        var scope = module == null ? GlobalSearchScope.projectScope(project) : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);
        Set<String> packages = new LinkedHashSet<>();
        FileBasedIndex index = FileBasedIndex.getInstance();
        // Fetch only indexed configuration files with alias/package entries.
        int configurations = 0;
        for (var file : index.getContainingFiles(TypeAliasIndex.NAME,TypeAliasIndex.CONFIG,scope)) {
            ProgressManager.checkCanceled();
            if (++configurations > 128) break;
            index.getFileData(TypeAliasIndex.NAME,file,project).forEach((key,value) -> {
                if (key.equals(TypeAliasIndex.PACKAGES)) packages.addAll(Arrays.asList(value.split(";")));
                else if (key.startsWith("#implicit:")) {
                    PsiClass cls=JavaPsiFacade.getInstance(project).findClass(value,scope);
                    String alias=value.substring(value.lastIndexOf('.')+1);
                    if(cls!=null)alias=classAlias(cls);
                    add(candidates,alias,value);
                }else if (!key.equals(TypeAliasIndex.CONFIG)) add(candidates,key,value);
            });
        }
        var settings = AssistantSettings.get(project);
        for (String value : settings.typeAliasPackages.split("[,;\\s]+")) if (!value.isBlank()) packages.add(value);
        for (String line : settings.typeAliasBindings.split("\\R")) {
            int split = line.indexOf('=');
            if (split > 0) add(candidates, line.substring(0,split).trim(), line.substring(split+1).trim());
        }
        Deque<String> queue = new ArrayDeque<>(packages);
        Set<String> visited = new HashSet<>();
        int classes = 0;
        while (!queue.isEmpty() && visited.size() < 256 && classes < 4096) {
            ProgressManager.checkCanceled();
            String name = queue.removeFirst(); if (!visited.add(name)) continue;
            PsiPackage pkg = JavaPsiFacade.getInstance(project).findPackage(name);
            if (pkg == null) continue;
            for (PsiClass cls : pkg.getClasses(scope)) {
                if (++classes > 4096) break;
                if (cls.isInterface() || cls.hasModifierProperty(PsiModifier.ABSTRACT) || cls.getQualifiedName() == null) continue;
                add(candidates,classAlias(cls),cls.getQualifiedName());
            }
            for (PsiPackage child : pkg.getSubPackages(scope)) queue.addLast(child.getQualifiedName());
        }
        Map<String,String> result = new TreeMap<>();
        candidates.forEach((key,values) -> { if (values.size() == 1) result.put(key,values.iterator().next()); });
        return Collections.unmodifiableMap(result);
    }
    private static void add(Map<String,Set<String>> values, String alias, String type) {
        if (alias != null && !alias.isBlank() && type != null && !type.isBlank()) values.computeIfAbsent(alias.toLowerCase(Locale.ROOT), key -> new HashSet<>()).add(type);
    }
    private static String classAlias(PsiClass cls){
        PsiAnnotation annotation=cls.getAnnotation("org.apache.ibatis.type.Alias");
        if(annotation!=null && annotation.findAttributeValue("value") instanceof PsiLiteralExpression literal && literal.getValue() instanceof String value)return value;
        return cls.getName();
    }
}
