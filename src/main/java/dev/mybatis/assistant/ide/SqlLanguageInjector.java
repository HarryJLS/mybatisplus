package dev.mybatis.assistant.ide;

import com.intellij.lang.Language;
import com.intellij.lang.injection.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import java.util.*;
import java.util.regex.*;
import org.jetbrains.annotations.NotNull;

/** Optional IDEA Database integration. Only static SQL with bound placeholders is injected. */
public final class SqlLanguageInjector implements MultiHostInjector {
    public record Place(TextRange range,String prefix) {}
    @Override public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar,@NotNull PsiElement element){
        Language sql=Language.findLanguageByID("SQL");if(sql==null)return;
        List<PsiLanguageInjectionHost> hosts=new ArrayList<>();
        if(element instanceof XmlTag tag){
            if(!MapperModel.isStatement(tag) || MapperModel.isLegacy(MapperModel.root(tag)) || tag.getSubTags().length>0)return;
            for(PsiElement child:tag.getChildren())if(child instanceof XmlText && child instanceof PsiLanguageInjectionHost host)hosts.add(host);
        }else if(element instanceof PsiLiteralExpression literal && literal instanceof PsiLanguageInjectionHost host){
            String text=AnnotationSql.text(literal);if(AnnotationSql.method(literal)==null || text==null || text.contains("<script>"))return;hosts.add(host);
            PsiAnnotationMemberValue value=AnnotationSql.annotation(literal).findAttributeValue("value");
            if(value!=literal && !(value instanceof PsiArrayInitializerMemberValue array && array.getInitializers().length==1 && array.getInitializers()[0]==literal))return;
        }else return;
        Map<PsiLanguageInjectionHost,List<Place>> places=new LinkedHashMap<>();
        for(PsiLanguageInjectionHost host:hosts){List<Place> ranges=places(host);if(ranges.isEmpty())return;places.put(host,ranges);}
        if(places.isEmpty())return;
        registrar.startInjecting(sql);
        places.forEach((host,ranges)->ranges.forEach(p->registrar.addPlace(p.prefix(),null,host,p.range())));
        registrar.doneInjecting();
    }
    @Override public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn(){return List.of(XmlTag.class,PsiLiteralExpression.class);}
    public static List<Place> places(PsiLanguageInjectionHost host){
        TextRange content=ElementManipulators.getValueTextRange(host);var escaper=host.createLiteralTextEscaper();StringBuilder decoded=new StringBuilder();
        if(!escaper.decode(content,decoded) || decoded.length()>262144 || decoded.indexOf("${")>=0)return List.of();
        List<Place> result=new ArrayList<>();Matcher matcher=Pattern.compile("#\\{[^}]*}").matcher(decoded);int start=0;String prefix="";
        while(matcher.find()){
            int a=escaper.getOffsetInHost(start,content),b=escaper.getOffsetInHost(matcher.start(),content);if(a<0 || b<a)return List.of();
            result.add(new Place(new TextRange(a,b),prefix));prefix="?";start=matcher.end();
        }
        int a=escaper.getOffsetInHost(start,content),b=escaper.getOffsetInHost(decoded.length(),content);if(a<0 || b<a)return List.of();
        result.add(new Place(new TextRange(a,b),prefix));return result;
    }
}
