package dev.mybatis.assistant.ide;

import com.intellij.lang.Language;
import com.intellij.lang.injection.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import java.util.*;
import java.util.regex.*;
import org.jetbrains.annotations.NotNull;

/** Optional SQL language integration; dynamic XML hosts keep their representative branch context. */
public final class SqlLanguageInjector implements MultiHostInjector {
    static final com.intellij.openapi.util.Key<Boolean> XML_SQL=com.intellij.openapi.util.Key.create("mybatis.assistant.xml.sql.injection");
    public record Place(TextRange range,String prefix) {}
    @Override public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar,@NotNull PsiElement element){
        Language sql=Language.findLanguageByID("SQL");if(sql==null || com.intellij.openapi.project.DumbService.isDumb(element.getProject()))return;
        if(element instanceof XmlText text){
            if(!(text instanceof PsiLanguageInjectionHost host))return;
            var places=DynamicSqlEditing.places(text);if(places.isEmpty())return;registrar.startInjecting(sql).putInjectedFileUserData(XML_SQL,true).frankensteinInjection(true);
            for(var place:places)registrar.addPlace(place.prefix(),place.suffix(),host,place.range());registrar.doneInjecting();
            return;
        }
        List<PsiLanguageInjectionHost> hosts=new ArrayList<>();
        if(element instanceof PsiLiteralExpression literal && literal instanceof PsiLanguageInjectionHost literalHost){
            String text=AnnotationSql.text(literal);if(AnnotationSql.method(literal)==null || text==null || text.contains("<script>"))return;hosts.add(literalHost);
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
    @Override public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn(){return List.of(XmlText.class,PsiLiteralExpression.class);}
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
