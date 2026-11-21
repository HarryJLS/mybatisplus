package dev.mybatis.assistant.ide;

import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.*;
import java.util.*;

/** Literal MapperScan configuration and targeted package indexes; no project-file traversal. */
public final class SpringMapperModel {
    private SpringMapperModel() {}
    public record Rule(List<String> packages,String annotationClass,String markerInterface) {}
    public record Config(List<Rule> rules,boolean skippedDynamic) {}
    private static final com.intellij.openapi.util.Key<CachedValue<Config>> CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.spring.scan");
    private static final com.intellij.openapi.util.Key<CachedValue<List<SmartPsiElementPointer<PsiClass>>>> MAPPERS=com.intellij.openapi.util.Key.create("mybatis.assistant.spring.mappers");
    public static Config config(PsiClass owner){return CachedValuesManager.getManager(owner.getProject()).getCachedValue(owner,CACHE,()->CachedValueProvider.Result.create(read(owner),PsiModificationTracker.MODIFICATION_COUNT),false);}
    private static Config read(PsiClass owner){
        List<Rule> rules=new ArrayList<>();boolean[] skipped={false};
        for(PsiAnnotation annotation:owner.getAnnotations()){
            if("org.mybatis.spring.annotation.MapperScan".equals(annotation.getQualifiedName()))readScan(owner,annotation,rules,skipped);
            if("org.mybatis.spring.annotation.MapperScans".equals(annotation.getQualifiedName())){
                PsiAnnotationMemberValue value=annotation.findAttributeValue("value");
                PsiAnnotationMemberValue[] entries=value instanceof PsiArrayInitializerMemberValue array?array.getInitializers():new PsiAnnotationMemberValue[]{value};
                for(PsiAnnotationMemberValue entry:entries)if(entry instanceof PsiAnnotation scan)readScan(owner,scan,rules,skipped);else skipped[0]=true;
            }
        }return new Config(List.copyOf(rules),skipped[0]);
    }
    private static void readScan(PsiClass owner,PsiAnnotation scan,List<Rule> rules,boolean[] skipped){
        boolean[] local={false};readRule(owner,scan,rules,local);skipped[0]|=local[0];
    }
    private static void readRule(PsiClass owner,PsiAnnotation scan,List<Rule> rules,boolean[] skipped){
        LinkedHashSet<String> packages=new LinkedHashSet<>();
        for(String attribute:List.of("value","basePackages")){
            PsiAnnotationMemberValue declared=scan.findDeclaredAttributeValue(attribute);if(declared==null)continue;
            for(PsiAnnotationMemberValue item:values(declared)){
                Object constant=JavaPsiFacade.getInstance(owner.getProject()).getConstantEvaluationHelper().computeConstantExpression(item);
                if(constant instanceof String text && !text.contains("${") && !text.contains("*"))for(String p:text.split("[,;\\s]+")){if(!p.isBlank())packages.add(p);}else skipped[0]=true;
            }
        }
        PsiAnnotationMemberValue classes=scan.findDeclaredAttributeValue("basePackageClasses");
        if(classes!=null)for(PsiAnnotationMemberValue item:values(classes)){String name=className(item);if(name==null)skipped[0]=true;else packages.add(packageName(name));}
        if(packages.isEmpty() && !skipped[0] && owner.getQualifiedName()!=null)packages.add(packageName(owner.getQualifiedName()));
        String annotation=className(scan.findAttributeValue("annotationClass")),marker=className(scan.findAttributeValue("markerInterface"));
        if("java.lang.annotation.Annotation".equals(annotation))annotation=null;
        if("java.lang.Class".equals(marker))marker=null;
        if(scan.findDeclaredAttributeValue("annotationClass")!=null && annotation==null || scan.findDeclaredAttributeValue("markerInterface")!=null && marker==null)skipped[0]=true;
        if(packages.contains(""))skipped[0]=true;
        if(scan.findDeclaredAttributeValue("nameGenerator")!=null || scan.findDeclaredAttributeValue("factoryBean")!=null){skipped[0]=true;return;}
        if(!skipped[0] && !packages.isEmpty())rules.add(new Rule(List.copyOf(packages),annotation,marker));
    }
    private static PsiAnnotationMemberValue[] values(PsiAnnotationMemberValue value){return value instanceof PsiArrayInitializerMemberValue array?array.getInitializers():new PsiAnnotationMemberValue[]{value};}
    private static String className(PsiAnnotationMemberValue value){
        if(value instanceof PsiClassObjectAccessExpression clazz && clazz.getOperand().getType() instanceof PsiClassType type){PsiClass cls=type.resolve();return cls==null?null:cls.getQualifiedName();}return null;
    }
    public static String packageName(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(0,dot);}
    public static List<PsiClass> mappers(PsiClass owner){
        if(DumbService.isDumb(owner.getProject()) || !AssistantSettings.get(owner.getProject()).springIntegration)return List.of();
        List<SmartPsiElementPointer<PsiClass>> pointers=CachedValuesManager.getManager(owner.getProject()).getCachedValue(owner,MAPPERS,()->CachedValueProvider.Result.create(loadMappers(owner).stream().map(cls->SmartPointerManager.getInstance(owner.getProject()).createSmartPsiElementPointer(cls)).toList(),PsiModificationTracker.MODIFICATION_COUNT,owner.getProject().getService(AssistantSettings.class)),false);
        return pointers.stream().map(SmartPsiElementPointer::getElement).filter(Objects::nonNull).toList();
    }
    private static List<PsiClass> loadMappers(PsiClass owner){
        var module=ModuleUtilCore.findModuleForPsiElement(owner);GlobalSearchScope scope=module==null?GlobalSearchScope.projectScope(owner.getProject()):GlobalSearchScope.moduleWithDependenciesScope(module);
        Set<PsiClass> result=new LinkedHashSet<>();int[] budget={0};
        for(Rule rule:config(owner).rules()){
            Deque<String> queue=new ArrayDeque<>(rule.packages());Set<String> seen=new HashSet<>();
            while(!queue.isEmpty()){
                ProgressManager.checkCanceled();if(seen.size()>=256 || budget[0]>=4096)break;String name=queue.removeFirst();if(!seen.add(name))continue;
                PsiPackage pkg=JavaPsiFacade.getInstance(owner.getProject()).findPackage(name);if(pkg==null)continue;
                for(PsiClass cls:pkg.getClasses(scope)){
                    if(++budget[0]>4096)break;if(!cls.isInterface() || cls.getQualifiedName()==null)continue;
                    boolean annotation=rule.annotationClass()!=null && cls.hasAnnotation(rule.annotationClass());
                    PsiClass marker=rule.markerInterface()==null?null:MapperModel.classByName(owner.getProject(),rule.markerInterface());
                    boolean marked=marker!=null && cls.isInheritor(marker,true);
                    if(rule.annotationClass()==null && rule.markerInterface()==null || annotation || marked)result.add(cls);
                }
                for(PsiPackage child:pkg.getSubPackages(scope))queue.addLast(child.getQualifiedName());
            }
        }return List.copyOf(result);
    }
}
