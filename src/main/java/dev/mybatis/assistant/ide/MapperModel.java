package dev.mybatis.assistant.ide;

import com.intellij.openapi.project.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.*;
import com.intellij.util.indexing.FileBasedIndex;
import java.util.*;
import dev.mybatis.assistant.core.NamespaceScanner;

public final class MapperModel {
    private MapperModel(){}
    public static final Set<String> STATEMENTS=Set.of("select","insert","update","delete");
    private static final Set<String> LEGACY_STATEMENTS=Set.of("statement","procedure");
    private static final Set<String> SQL_ANNOTATIONS=Set.of("Select","Insert","Update","Delete","SelectProvider","InsertProvider","UpdateProvider","DeleteProvider");
    public record Metadata(Map<String,List<XmlTag>> tags){}
    private static final com.intellij.openapi.util.Key<CachedValue<List<SmartPsiElementPointer<XmlTag>>>> ROOTS_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.xml.roots");
    private static List<XmlTag> xmls(PsiClass cls){
        List<SmartPsiElementPointer<XmlTag>> pointers=CachedValuesManager.getManager(cls.getProject()).getCachedValue(cls,ROOTS_CACHE,()->CachedValueProvider.Result.create(
                classRoots(cls).stream().map(root->SmartPointerManager.getInstance(cls.getProject()).createSmartPsiElementPointer(root)).toList(),cls.getContainingFile(),cls.getProject().getService(AssistantSettings.class),
                (com.intellij.openapi.util.ModificationTracker)()->FileBasedIndex.getInstance().getIndexModificationStamp(MapperNamespaceIndex.NAME,cls.getProject())),false);
        return pointers.stream().map(SmartPsiElementPointer::getElement).filter(Objects::nonNull).toList();
    }
    public static List<XmlTag> mapperRoots(PsiClass cls){return cls==null?List.of():xmls(cls);}
    public static boolean isLegacy(XmlTag root){return root!=null && "sqlMap".equals(root.getName());}
    public static boolean isStatement(XmlTag tag){return tag!=null && (STATEMENTS.contains(tag.getName()) || isLegacy(root(tag)) && LEGACY_STATEMENTS.contains(tag.getName()));}
    public static String localId(XmlTag root,String id){
        if(id==null || !isLegacy(root))return id;
        String namespace=root.getAttributeValue("namespace");
        if(namespace!=null && id.startsWith(namespace+"."))return id.substring(namespace.length()+1);
        String filename=root.getContainingFile().getName();int dot=filename.lastIndexOf('.');String base=dot<0?filename:filename.substring(0,dot);
        return namespace==null && id.startsWith(base+".")?id.substring(base.length()+1):id;
    }
    private static List<XmlTag> classRoots(PsiClass cls){
        if(cls.getQualifiedName()==null)return List.of();
        Set<XmlTag> roots=new LinkedHashSet<>(xmls(cls.getProject(),cls.getQualifiedName()));
        String configured=cls.getProject().getService(AssistantSettings.class).namespaceBindings().get(cls.getQualifiedName());
        if(configured!=null){roots.addAll(xmls(cls.getProject(),configured));for(XmlTag root:indexedRoots(cls.getProject(),NamespaceScanner.LEGACY_FILE+configured))if(root.getAttributeValue("namespace")==null)roots.add(root);}
        PsiClass simple=cls.getName()==null?null:simpleInterface(cls.getProject(),cls.getName());
        if(simple!=null && simple.isEquivalentTo(cls)){
            roots.addAll(indexedRoots(cls.getProject(),NamespaceScanner.LEGACY_NAMESPACE+cls.getName()));
            for(XmlTag root:indexedRoots(cls.getProject(),NamespaceScanner.LEGACY_FILE+cls.getName()))if(root.getAttributeValue("namespace")==null)roots.add(root);
        }
        return List.copyOf(roots);
    }
    private static PsiClass simpleInterface(Project project,String name){
        if(name==null || name.contains(".") || DumbService.isDumb(project))return null;
        PsiClass found=null;
        for(PsiClass candidate:PsiShortNamesCache.getInstance(project).getClassesByName(name,GlobalSearchScope.projectScope(project)))if(candidate.isInterface()){
            if(found!=null)return null;found=candidate;
        }
        return found;
    }
    public static XmlTag root(PsiElement e){
        if(e==null || !(e.getContainingFile() instanceof XmlFile f))return null;
        XmlTag r=f.getRootTag();return r!=null && ("mapper".equals(r.getName()) || "sqlMap".equals(r.getName()))?r:null;
    }
    public static Metadata metadata(XmlTag root){
        return CachedValuesManager.getCachedValue(root,()->{
            Map<String,List<XmlTag>> tags=new HashMap<>();
            for(XmlTag t:root.getSubTags()) {String id=t.getAttributeValue("id");if(id!=null)tags.computeIfAbsent(localId(root,id),k->new ArrayList<>()).add(t);}
            return CachedValueProvider.Result.create(new Metadata(tags),root.getContainingFile());
        });
    }
    public static List<XmlTag> xmls(Project project,String namespace){
        if(namespace==null || DumbService.isDumb(project))return List.of();
        return indexedRoots(project,namespace).stream().filter(r->namespace.equals(r.getAttributeValue("namespace"))).toList();
    }
    private static List<XmlTag> indexedRoots(Project project,String key){
        if(DumbService.isDumb(project))return List.of();
        List<XmlTag> roots=new ArrayList<>();
        for(var file:FileBasedIndex.getInstance().getContainingFiles(MapperNamespaceIndex.NAME,key,GlobalSearchScope.projectScope(project))){
            ProgressManager.checkCanceled();var psi=PsiManager.getInstance(project).findFile(file);
            if(psi instanceof XmlFile){XmlTag r=root(psi);if(r!=null)roots.add(r);}
        }
        return roots;
    }
    public static PsiClass mapperClass(XmlTag root){
        String namespace=root.getAttributeValue("namespace");PsiClass direct=classByName(root.getProject(),namespace);
        if(!isLegacy(root))return direct;
        if(direct!=null && direct.isInterface())return direct;
        PsiClass configured=null;
        for(var binding:root.getProject().getService(AssistantSettings.class).namespaceBindings().entrySet())if(Objects.equals(binding.getValue(),namespace) || namespace==null && root.getContainingFile().getName().equals(binding.getValue()+".xml")){
            PsiClass candidate=classByName(root.getProject(),binding.getKey());if(candidate!=null){if(configured!=null)return null;configured=candidate;}
        }
        if(configured!=null)return configured;
        if(namespace==null){String filename=root.getContainingFile().getName();int dot=filename.lastIndexOf('.');namespace=dot<0?filename:filename.substring(0,dot);}
        return simpleInterface(root.getProject(),namespace);
    }
    public static PsiClass typeClass(XmlTag context,String name){
        PsiClass direct=classByName(context.getProject(),name);if(direct!=null)return direct;
        XmlTag root=root(context);if(root!=null && name!=null)for(XmlTag alias:root.findSubTags("typeAlias"))if(name.equals(alias.getAttributeValue("alias")))return classByName(context.getProject(),alias.getAttributeValue("type"));
        return classByName(context.getProject(),TypeAliases.resolve(context,name));
    }
    public static PsiClass classByName(Project project,String name){
        if(name==null || name.isBlank() || DumbService.isDumb(project))return null;
        return JavaPsiFacade.getInstance(project).findClass(name,GlobalSearchScope.allScope(project));
    }
    public static List<XmlTag> statements(PsiMethod method){
        PsiClass cls=method.getContainingClass();if(cls==null)return List.of();
        List<XmlTag> result=new ArrayList<>();
        for(XmlTag root:xmls(cls))
            for(XmlTag t:metadata(root).tags().getOrDefault(method.getName(),List.of()))if(isStatement(t))result.add(t);
        return result;
    }
    public static PsiMethod method(XmlTag tag){
        XmlTag root=root(tag);if(root==null)return null;PsiClass cls=mapperClass(root);String id=localId(root,tag.getAttributeValue("id"));
        if(cls==null || id==null)return null;
        PsiMethod[] methods=cls.findMethodsByName(id,true);if(methods.length!=1)return null;
        if(Arrays.stream(methods[0].getParameterList().getParameters()).anyMatch(p->p.getType().getCanonicalText().startsWith("kotlin.coroutines.Continuation")))return null;
        return methods[0];
    }
    public static XmlTag statement(PsiElement element){
        XmlTag t=PsiTreeUtil.getParentOfType(element,XmlTag.class,false);
        while(t!=null && !isStatement(t))t=t.getParentTag();return root(t)==null?null:t;
    }
    public static XmlTag definition(XmlTag context,String name,String kind){
        XmlTag root=root(context);if(root==null || name==null)return null;
        for(XmlTag t:metadata(root).tags().getOrDefault(localId(root,name),List.of()))if(kind.equals(t.getName()))return t;
        int dot=name.lastIndexOf('.');String id=dot<0?name:name.substring(dot+1);
        List<XmlTag> roots=dot<0?List.of(root):xmls(root.getProject(),name.substring(0,dot));
        for(XmlTag r:roots)for(XmlTag t:metadata(r).tags().getOrDefault(localId(r,id),List.of()))if(kind.equals(t.getName()))return t;
        return null;
    }
    public static List<XmlTag> sqlTargets(Project project,String statementId){
        LinkedHashSet<XmlTag> targets=new LinkedHashSet<>();
        int dot=statementId.lastIndexOf('.');
        if(dot>0)for(XmlTag root:xmls(project,statementId.substring(0,dot)))for(XmlTag t:metadata(root).tags().getOrDefault(statementId.substring(dot+1),List.of()))if(isStatement(t))targets.add(t);
        for(XmlTag root:indexedRoots(project,NamespaceScanner.STATEMENT+statementId))for(XmlTag t:root.getSubTags())if(isStatement(t)){
            String id=t.getAttributeValue("id"),ns=root.getAttributeValue("namespace"),local=localId(root,id);
            if(id==null)continue;
            if(statementId.equals(id) || statementId.equals(local) || ns!=null && statementId.equals(ns+"."+local))targets.add(t);
        }
        return List.copyOf(targets);
    }
    public static boolean mapper(PsiClass cls){
        return cls!=null && cls.isInterface() && (cls.hasAnnotation("org.apache.ibatis.annotations.Mapper") || !xmls(cls).isEmpty());
    }
    public static boolean annotated(PsiMethod method){
        for(PsiAnnotation a:method.getAnnotations()){String name=a.getQualifiedName();if(name!=null && name.startsWith("org.apache.ibatis.annotations.") && SQL_ANNOTATIONS.contains(name.substring(name.lastIndexOf('.')+1)))return true;}
        return false;
    }
    public static PsiType entityType(PsiType type){
        if(type instanceof PsiWildcardType wildcard)return entityType(wildcard.getBound());
        if(type instanceof PsiCapturedWildcardType captured)return entityType(captured.getUpperBound());
        if(type instanceof PsiArrayType array)return array.getComponentType();
        if(type instanceof PsiClassType c){
            if(c.getParameters().length>0 && Set.of("java.util.List","java.util.Collection","java.util.Set","java.lang.Iterable","java.util.Optional").contains(c.rawType().getCanonicalText()))return entityType(c.getParameters()[0]);
            PsiClass cls=c.resolve();
            if(cls!=null && (InheritanceUtil.isInheritor(cls,"java.lang.Iterable") || InheritanceUtil.isInheritor(cls,"java.util.Optional"))){
                if(c.getParameters().length>0)return c.getParameters()[0];
            }
        }return type;
    }
}
