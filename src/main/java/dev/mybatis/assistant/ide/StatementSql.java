package dev.mybatis.assistant.ide;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.*;
import dev.mybatis.assistant.core.SqlProjection;
import java.util.*;

/** Bounded expansion, with dependencies on the XML files actually read. */
final class StatementSql {
    private StatementSql() {}
    private record Projection(SqlProjection.Result result,List<SmartPsiElementPointer<PsiFile>> files) {}
    private static final Key<CachedValue<Projection>> CACHE = Key.create("mybatis.assistant.projection");
    private static final Key<CachedValue<SqlProjection.Result>> COLUMNS_CACHE = Key.create("mybatis.assistant.result.columns");
    static SqlProjection.Result projection(XmlTag statement) {return data(statement).result();}
    private static Projection data(XmlTag statement) {
        return CachedValuesManager.getManager(statement.getProject()).getCachedValue(statement,CACHE,() -> {
            Set<PsiFile> files=new LinkedHashSet<>();files.add(statement.getContainingFile());
            StringBuilder sql = new StringBuilder();SqlProjection.Result result;
            try {append(statement,sql,new HashSet<>(),0,new int[]{0},files);result=SqlProjection.parse(sql.toString());}
            catch (IllegalArgumentException e) {result=new SqlProjection.Result(List.of(),false,e.getMessage());}
            var manager=SmartPointerManager.getInstance(statement.getProject());
            Projection value=new Projection(result,files.stream().map(manager::createSmartPsiElementPointer).toList());
            List<Object> dependencies=new ArrayList<>(files);
            // Missing includes must be retried when a matching namespace file appears.
            dependencies.add(ModelDependencies.namespaces(statement.getProject()));
            return CachedValueProvider.Result.create(value,dependencies.toArray());
        },false);
    }
    static SqlProjection.Result columns(XmlTag resultMap) {
        return CachedValuesManager.getManager(resultMap.getProject()).getCachedValue(resultMap,COLUMNS_CACHE,()->{
            Set<PsiFile> files=new LinkedHashSet<>();files.add(resultMap.getContainingFile());
            SqlProjection.Result result=loadColumns(resultMap,files,new HashSet<>(),0);
            List<Object> dependencies=new ArrayList<>(files);
            dependencies.add(ModelDependencies.namespaces(resultMap.getProject()));
            dependencies.add(ModelDependencies.types(resultMap.getProject()));
            dependencies.add(MapperUsageIndex.tracker(resultMap.getProject()));
            dependencies.add(ProjectRootModificationTracker.getInstance(resultMap.getProject()));
            dependencies.add(resultMap.getProject().getService(AssistantSettings.class));
            return CachedValueProvider.Result.create(result,dependencies.toArray());
        },false);
    }
    private static SqlProjection.Result loadColumns(XmlTag resultMap,Set<PsiFile> files,Set<XmlTag> stack,int depth) {
        if(depth>32 || !stack.add(resultMap))return new SqlProjection.Result(List.of(),false,"嵌套 resultMap 循环或超出上限");
        try {
            XmlTag owner=MapperModel.root(resultMap);String id=resultMap.getAttributeValue("id"),namespace=owner.getAttributeValue("namespace");
            if(id==null)return new SqlProjection.Result(List.of(),false,"resultMap 没有 id");
            Set<XmlTag> roots=new LinkedHashSet<>(List.of(owner));
            if(namespace!=null){roots.addAll(MapperModel.xmls(owner.getProject(),namespace));roots.addAll(MapperUsageIndex.roots(resultMap,"map",namespace+"."+id));}
            List<String> columns=new ArrayList<>();boolean found=false,complete=true;int steps=0;
            for(XmlTag root:roots){
                files.add(root.getContainingFile());if(files.size()>128)return new SqlProjection.Result(List.copyOf(columns),false,"映射依赖文件超过上限");
                List<XmlTag> tags=new ArrayList<>(List.of(root));
                for(int i=0;i<tags.size();i++){
                    XmlTag tag=tags.get(i);ProgressManager.checkCanceled();if(++steps>4096)return new SqlProjection.Result(List.copyOf(columns),false,"映射引用节点超过上限");tags.addAll(Arrays.asList(tag.getSubTags()));
                    String refs=tag.getAttributeValue(tag.getName().equals("resultMap")?"extends":"resultMap");if(refs==null)continue;
                    for(String ref:refs.split("[,\\s]+"))if(MapperModel.definition(tag,ref,"resultMap")==resultMap){
                        SqlProjection.Result projection;String prefix="";
                        if(tag.getName().equals("select")){
                            Projection data=data(tag);projection=data.result();for(var pointer:data.files()){PsiFile file=pointer.getElement();if(file!=null)files.add(file);}
                        }else{
                            XmlTag outer=tag.getName().equals("resultMap")?tag:tag.getParentTag();while(outer!=null && !outer.getName().equals("resultMap"))outer=outer.getParentTag();if(outer==null)continue;
                            projection=loadColumns(outer,files,stack,depth+1);
                            if(tag!=outer)prefix=columnPrefix(tag)+Objects.requireNonNullElse(tag.getAttributeValue("columnPrefix"),"");
                        }
                        found=true;complete&=projection.complete();for(String column:projection.columns())if(column.startsWith(prefix))columns.add(column.substring(prefix.length()));
                    }
                }
            }
            return new SqlProjection.Result(List.copyOf(new LinkedHashSet<>(columns)),found && complete,found?"部分 SELECT 列无法静态解析":"没有明确引用此 resultMap 的 SELECT");
        }finally{stack.remove(resultMap);}
    }
    static String columnPrefix(XmlTag field){
        String prefix="";
        for(XmlTag parent=field.getParentTag();parent!=null && !parent.getName().equals("resultMap");parent=parent.getParentTag())prefix=Objects.requireNonNullElse(parent.getAttributeValue("columnPrefix"),"")+prefix;
        return prefix;
    }
    private static void append(XmlTag tag,StringBuilder sql,Set<XmlTag> stack,int depth,int[] steps,Set<PsiFile> files) {
        ProgressManager.checkCanceled();files.add(tag.getContainingFile());
        if(depth>32 || ++steps[0]>2048)throw new IllegalArgumentException("SQL 片段展开超过上限");
        for(PsiElement child:tag.getChildren()) {
            if(child instanceof XmlText text)text(sql,text.getValue());
            else if(child instanceof XmlTag nested) {
                if(nested.getName().equals("include")) {
                    String ref=nested.getAttributeValue("refid");
                    if(ref==null || ref.contains("${")){text(sql," ? ");continue;}
                    XmlTag target=MapperModel.definition(nested,ref,"sql");
                    if(target==null || !stack.add(target))throw new IllegalArgumentException("SQL 片段缺失或循环引用");
                    text(sql," ");append(target,sql,stack,depth+1,steps,files);text(sql," ");stack.remove(target);
                }else if(!nested.getName().equals("bind") && !nested.getName().equals("selectKey")){
                    text(sql," ? ");append(nested,sql,stack,depth+1,steps,files);text(sql," ? ");
                }
            }
        }
    }
    private static void text(StringBuilder target,String value){
        if((long)target.length()+value.length()>262144)throw new IllegalArgumentException("SQL 片段展开超过上限");
        target.append(value);
    }
}
