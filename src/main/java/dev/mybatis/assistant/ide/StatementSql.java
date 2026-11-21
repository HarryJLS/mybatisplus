package dev.mybatis.assistant.ide;

import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.*;
import dev.mybatis.assistant.core.SqlProjection;
import java.util.*;

/** Bounded expansion of explicit include references; dynamic output is marked, not guessed. */
final class StatementSql {
    private StatementSql() {}
    private static final Key<CachedValue<SqlProjection.Result>> CACHE = Key.create("mybatis.assistant.projection");
    private static final Key<CachedValue<SqlProjection.Result>> COLUMNS_CACHE = Key.create("mybatis.assistant.result.columns");
    static SqlProjection.Result projection(XmlTag statement) {
        return CachedValuesManager.getManager(statement.getProject()).getCachedValue(statement,CACHE,() -> {
            StringBuilder sql = new StringBuilder();
            try { append(statement,sql,new HashSet<>(),0,new int[]{0}); }
            catch (IllegalArgumentException e) { return CachedValueProvider.Result.create(new SqlProjection.Result(List.of(),false,e.getMessage()),PsiModificationTracker.MODIFICATION_COUNT); }
            return CachedValueProvider.Result.create(SqlProjection.parse(sql.toString()),PsiModificationTracker.MODIFICATION_COUNT);
        },false);
    }
    static SqlProjection.Result columns(XmlTag resultMap) {
        return CachedValuesManager.getManager(resultMap.getProject()).getCachedValue(resultMap,COLUMNS_CACHE,()->CachedValueProvider.Result.create(loadColumns(resultMap),PsiModificationTracker.MODIFICATION_COUNT),false);
    }
    private static SqlProjection.Result loadColumns(XmlTag resultMap) {
        Set<XmlTag> roots = new LinkedHashSet<>(List.of(MapperModel.root(resultMap)));
        // Reverse references are resolved only inside this mapper's files, never across the project.
        PsiClass mapper = MapperModel.mapperClass(roots.iterator().next());
        if (mapper != null) roots.addAll(MapperModel.mapperRoots(mapper));
        List<String> columns = new ArrayList<>();
        boolean complete = true, found = false;
        for (XmlTag root : roots) for (XmlTag statement : root.getSubTags()) {
            if (!statement.getName().equals("select")) continue;
            String refs = statement.getAttributeValue("resultMap");
            if (refs == null) continue;
            for (String ref : refs.split("[,\\s]+")) if (MapperModel.definition(statement,ref,"resultMap") == resultMap) {
                found = true;
                SqlProjection.Result r = projection(statement);
                columns.addAll(r.columns()); complete &= r.complete();
            }
        }
        return new SqlProjection.Result(List.copyOf(new LinkedHashSet<>(columns)),found && complete,found ? "部分 SELECT 列无法静态解析" : "没有本 Mapper 内引用此 resultMap 的 SELECT");
    }
    static String columnPrefix(XmlTag field){
        String prefix="";
        for(XmlTag parent=field.getParentTag();parent!=null && !parent.getName().equals("resultMap");parent=parent.getParentTag())prefix=Objects.requireNonNullElse(parent.getAttributeValue("columnPrefix"),"")+prefix;
        return prefix;
    }
    private static void append(XmlTag tag, StringBuilder sql, Set<XmlTag> stack, int depth, int[] steps) {
        if (depth > 32 || ++steps[0] > 2048 || sql.length() > 262144) throw new IllegalArgumentException("SQL 片段展开超过上限");
        for (PsiElement child : tag.getChildren()) {
            if (child instanceof XmlText text) sql.append(text.getValue());
            else if (child instanceof XmlTag nested) {
                if (nested.getName().equals("include")) {
                    String ref = nested.getAttributeValue("refid");
                    if (ref == null || ref.contains("${")) { sql.append(" ? "); continue; }
                    XmlTag target = MapperModel.definition(nested,ref,"sql");
                    if (target == null || !stack.add(target)) throw new IllegalArgumentException("SQL 片段缺失或循环引用");
                    sql.append(' '); append(target,sql,stack,depth+1,steps); sql.append(' '); stack.remove(target);
                } else if (nested.getName().equals("bind") || nested.getName().equals("selectKey")) {
                    // Neither produces columns in the main statement.
                } else {
                    // A marker before FROM makes projections incomplete; tags after FROM do not affect output names.
                    sql.append(" ? "); append(nested,sql,stack,depth+1,steps); sql.append(" ? ");
                }
            }
        }
    }
}
