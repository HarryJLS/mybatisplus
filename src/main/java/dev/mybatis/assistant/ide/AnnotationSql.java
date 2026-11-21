package dev.mybatis.assistant.ide;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.PsiModificationTracker;
import dev.mybatis.assistant.core.Schema;
import java.util.*;
import java.util.regex.*;

/** Plain constant annotation SQL shares the Java binding model; no project code is executed. */
final class AnnotationSql {
    private AnnotationSql() {}
    private static final Set<String> NAMES=Set.of("Select","Insert","Update","Delete");
    private static final com.intellij.openapi.util.Key<CachedValue<String>> SQL_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.annotation.sql");
    static PsiAnnotation annotation(PsiElement host){
        PsiAnnotation a=PsiTreeUtil.getParentOfType(host,PsiAnnotation.class,false);
        String name=a==null?null:a.getQualifiedName();
        return name!=null && NAMES.stream().anyMatch(n->name.equals("org.apache.ibatis.annotations."+n))?a:null;
    }
    static PsiMethod method(PsiElement host){return annotation(host)==null?null:PsiTreeUtil.getParentOfType(host,PsiMethod.class);}
    static String text(PsiLiteralExpression host){return host.getValue() instanceof String s?s:null;}
    static TextRange range(PsiLiteralExpression host,int start,int end){
        TextRange content=ElementManipulators.getValueTextRange(host);
        if(!(host instanceof PsiLanguageInjectionHost injectionHost))return null;
        LiteralTextEscaper<? extends PsiLanguageInjectionHost> escaper=injectionHost.createLiteralTextEscaper();
        if(!escaper.decode(content,new StringBuilder()))return null;
        int a=escaper.getOffsetInHost(start,content),b=escaper.getOffsetInHost(end,content);
        return a<0 || b<a?null:new TextRange(a,b);
    }
    static PsiReference[] references(PsiLiteralExpression host){
        String sql=text(host);PsiMethod method=method(host);
        if(sql==null || method==null || scripted(method))return PsiReference.EMPTY_ARRAY;
        List<PsiReference> refs=new ArrayList<>();Matcher placeholders=MapperReferences.PLACEHOLDER.matcher(sql);
        while(placeholders.find()){
            String path=placeholders.group(1);Matcher segments=Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*").matcher(path);
            while(segments.find()){
                if(segments.start()>0 && path.charAt(segments.start()-1)!='.')continue;
                String prefix=path.substring(0,segments.end());TextRange r=range(host,placeholders.start(1)+segments.start(),placeholders.start(1)+segments.end());if(r==null)continue;
                refs.add(new PsiReferenceBase<PsiLiteralExpression>(host,r,true){
                    @Override public PsiElement resolve(){var symbol=ParameterModel.of(AnnotationSql.method(myElement),null).resolve(prefix);return symbol==null?null:symbol.declaration();}
                    @Override public PsiElement handleElementRename(String name){PsiElement target=resolve();if(target instanceof PsiParameter p && ParameterModel.paramName(p)!=null)return myElement;return super.handleElementRename(name);}
                });
            }
        }
        return refs.toArray(PsiReference.EMPTY_ARRAY);
    }
    static String sql(PsiMethod method){
        return CachedValuesManager.getManager(method.getProject()).getCachedValue(method,SQL_CACHE,()->CachedValueProvider.Result.create(readSql(method),PsiModificationTracker.MODIFICATION_COUNT),false);
    }
    static boolean scripted(PsiMethod method){String text=sql(method);return text!=null && text.stripLeading().startsWith("<script>");}
    static boolean editableBinding(PsiMethod method){
        for(PsiAnnotation a:method.getAnnotations())if(annotation(a)!=null){
            PsiAnnotationMemberValue value=a.findAttributeValue("value");
            if(value instanceof PsiLiteralExpression)return true;
            return value instanceof PsiArrayInitializerMemberValue array && Arrays.stream(array.getInitializers()).allMatch(v->v instanceof PsiLiteralExpression);
        }return true;
    }
    private static String readSql(PsiMethod method){
        for(MapperLanguageSupport support:MapperLanguageSupport.EP.getExtensionList()){String value=support.annotationSql(method);if(value!=null)return value.length()>4*1024*1024?null:value;}
        for(PsiAnnotation annotation:method.getAnnotations()){
            if(annotation(annotation)==null)continue;
            PsiAnnotationMemberValue value=annotation.findAttributeValue("value");
            List<String> parts=new ArrayList<>();
            long size=0;
            PsiAnnotationMemberValue[] elements=value instanceof PsiArrayInitializerMemberValue array?array.getInitializers():new PsiAnnotationMemberValue[]{value};
            if(elements.length>1024)return null;
            for(PsiAnnotationMemberValue part:elements){
                if(part==null)return null;Object constant=JavaPsiFacade.getInstance(method.getProject()).getConstantEvaluationHelper().computeConstantExpression(part);
                if(!(constant instanceof String text) || (size+=text.length()+1)>4*1024*1024)return null;parts.add(text);
            }
            return String.join(" ",parts);
        }return null;
    }
    static String mapperXml(PsiMethod method){
        String sql=sql(method);if(sql==null)throw new IllegalArgumentException("只支持常量 @Select/@Insert/@Update/@Delete；Provider 需要项目运行时");
        String trimmed=sql.trim();
        if(trimmed.startsWith("<script>") && trimmed.endsWith("</script>"))sql=trimmed.substring(8,trimmed.length()-9);
        else sql=Schema.xml(sql);
        PsiClass cls=method.getContainingClass();
        return "<mapper namespace=\""+Schema.xml(cls==null?"annotation":Objects.requireNonNullElse(cls.getQualifiedName(),"annotation"))+"\"><select id=\""+method.getName()+"\">"+sql+"</select></mapper>";
    }
}
