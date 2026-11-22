package dev.mybatis.assistant.ide;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import com.intellij.util.ProcessingContext;
import java.util.*;
import java.util.regex.*;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

public final class MapperReferences extends PsiReferenceContributor {
    public static final Pattern PLACEHOLDER=Pattern.compile("[#$]\\{\\s*([\\p{L}_][\\p{L}\\p{N}_]*(?:\\[[^]{}]*])?(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*(?:\\[[^]{}]*])?)*)");
    public static final Pattern LEGACY_PLACEHOLDER=Pattern.compile("([#$])\\s*((?:[\\p{L}_][\\p{L}\\p{N}_]*(?:\\[[^]#$]*])?|\\[\\])(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*(?:\\[[^]#$]*])?)*)(?:\\s*[:,][^#$]*)?\\s*\\1");
    public static boolean parameterProperty(XmlTag tag){return Set.of("isEqual","isNotEqual","isGreaterThan","isGreaterEqual","isLessThan","isLessEqual","isNull","isNotNull","isEmpty","isNotEmpty","isPropertyAvailable","isNotPropertyAvailable","iterate").contains(tag.getName());}
    private static final Pattern WORD=Pattern.compile("[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)*");
    private static final Pattern SEGMENT=Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");
    private static final Pattern IDS=Pattern.compile("[^,\\s]+");
    private static final Set<String> KEYWORDS=Set.of("null","true","false","and","or","not","eq","ne","gt","ge","lt","le","empty","instanceof","_databaseId");
    @Override public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar){
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue.class),new PsiReferenceProvider(){
            @Override public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement e,@NotNull ProcessingContext context){return attribute((XmlAttributeValue)e);}
        });
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlText.class),new PsiReferenceProvider(){
            @Override public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement e,@NotNull ProcessingContext context){
                return text((XmlText)e);
            }
        });
    }
    public static PsiReference[] text(XmlText host){
        if(MapperModel.root(host)==null || DumbService.isDumb(host.getProject()))return PsiReference.EMPTY_ARRAY;
        boolean legacy=MapperModel.isLegacy(MapperModel.root(host));int group=legacy?2:1;
        List<PsiReference> refs=new ArrayList<>();Matcher m=(legacy?LEGACY_PLACEHOLDER:PLACEHOLDER).matcher(host.getText());
        while(m.find())addPath(refs,host,new TextRange(m.start(group),m.end(group)),m.group(group));
        return refs.toArray(PsiReference.EMPTY_ARRAY);
    }
    private static PsiReference[] attribute(XmlAttributeValue value){
        if(MapperModel.root(value)==null || DumbService.isDumb(value.getProject()))return PsiReference.EMPTY_ARRAY;
        if(!(value.getParent() instanceof XmlAttribute attr))return PsiReference.EMPTY_ARRAY;
        XmlTag tag=attr.getParent();String name=attr.getName(),text=value.getValue();
        TextRange range=value.getValueTextRange().shiftLeft(value.getTextRange().getStartOffset());List<PsiReference> refs=new ArrayList<>();
        switch(name){
            case "namespace" -> refs.add(new Ref(value,range,()->MapperModel.mapperClass(MapperModel.root(tag)),true));
            case "resultType","parameterType","type","ofType","javaType","resultClass","parameterClass","class" -> {
                refs.add(new Ref(value,range,()->MapperModel.typeClass(tag,text),text.contains(".")));
            }
            case "id" -> {if(MapperModel.isStatement(tag))refs.add(new Ref(value,localRange(range,text),()->MapperModel.method(tag),false));}
            case "refid" -> refs.add(new Ref(value,localRange(range,text),()->definitionId(MapperModel.definition(tag,text,"sql")),false));
            case "resultMap","extends","parameterMap" -> {
                Matcher tokens=IDS.matcher(text);
                while(tokens.find()){String id=tokens.group();String kind=name.equals("parameterMap")?"parameterMap":"resultMap";TextRange r=new TextRange(range.getStartOffset()+tokens.start(),range.getStartOffset()+tokens.end());refs.add(new Ref(value,localRange(r,id),()->definitionId(MapperModel.definition(tag,id,kind)),false));}
            }
            case "property" -> {if(parameterProperty(tag))addPath(refs,value,range,text);else refs.add(new Ref(value,range,()->{
                var symbol=ParameterModel.properties(MappingTypes.ownerType(tag)).get(text);return symbol==null?null:symbol.declaration();
            },false));}
            case "compareProperty" -> addPath(refs,value,range,text);
            case "keyProperty" -> addPath(refs,value,range,text);
            case "collection" -> addPath(refs,value,range,text);
            case "test","value" -> {
                if(!name.equals("value") || "bind".equals(tag.getName())){
                    Decoded decoded=decode(text);String expression=decoded.text();Matcher words=WORD.matcher(expression);
                    while(words.find()){
                        String word=words.group();if(KEYWORDS.contains(word) || insideString(expression,words.start()))continue;
                        int end=words.end();
                        if(end<expression.length() && expression.charAt(end)=='('){int dot=word.lastIndexOf('.');if(dot<0)continue;end=words.start()+dot;word=word.substring(0,dot);}
                        addPath(refs,value,new TextRange(range.getStartOffset()+decoded.offsets()[words.start()],range.getStartOffset()+decoded.offsets()[end]),word);
                    }
                }
            }
        }
        return refs.toArray(PsiReference.EMPTY_ARRAY);
    }
    private static boolean insideString(String text,int offset){
        char quote=0;for(int i=0;i<offset;i++){char c=text.charAt(i);if(c=='\\'){i++;continue;}if(quote==0 && (c=='\'' || c=='"'))quote=c;else if(c==quote)quote=0;}return quote!=0;
    }
    private record Decoded(String text,int[] offsets){}
    private static Decoded decode(String raw){
        StringBuilder decoded=new StringBuilder();List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<raw.length();){
            int end=raw.charAt(i)=='&'?raw.indexOf(';',i+1):-1;String entity=end>=0 && end-i<=16?raw.substring(i,end+1):"";
            String value=entity.isEmpty()?"":com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities(entity);
            if(!entity.isEmpty() && !value.equals(entity)){
                for(int j=0;j<value.length();j++){offsets.add(i);decoded.append(value.charAt(j));}i=end+1;
            }else{offsets.add(i);decoded.append(raw.charAt(i++));}
        }offsets.add(raw.length());return new Decoded(decoded.toString(),offsets.stream().mapToInt(Integer::intValue).toArray());
    }
    private static TextRange localRange(TextRange range,String text){return new TextRange(range.getStartOffset()+text.lastIndexOf('.')+1,range.getEndOffset());}
    private static PsiElement definitionId(XmlTag tag){return tag==null?null:tag.getAttribute("id").getValueElement();}
    private static void addPath(List<PsiReference> refs,PsiElement host,TextRange range,String path){
        // Each property segment gets its own range, so renaming a field preserves the object prefix.
        // Use positions from the original string rather than the normalized path.
        Matcher segment=SEGMENT.matcher(path);
        while(segment.find()){
            if(segment.start()>0 && path.charAt(segment.start()-1)!='.')continue;
            String full=path.substring(0,segment.end());
            refs.add(new Ref(host,new TextRange(range.getStartOffset()+segment.start(),range.getStartOffset()+segment.end()),()->{
                var symbol=ParameterModel.of(null,host).resolve(full);return symbol==null?null:symbol.declaration();
            },false));
        }
    }
    private static final class Ref extends PsiReferenceBase<PsiElement> {
        private final Supplier<PsiElement> resolver;private final boolean qualifiedClass;
        Ref(PsiElement host,TextRange range,Supplier<PsiElement> resolver,boolean qualifiedClass){super(host,range,true);this.resolver=resolver;this.qualifiedClass=qualifiedClass;}
        @Override public PsiElement resolve(){return resolver.get();}
        @Override public Object @NotNull [] getVariants(){return EMPTY_ARRAY;}
        @Override public PsiElement handleElementRename(@NotNull String newName){
            PsiElement resolved=resolve();
            if(resolved instanceof PsiParameter p && ParameterModel.paramName(p)!=null)return myElement;
            if(resolved instanceof PsiClass && !qualifiedClass)return myElement;
            if(qualifiedClass && resolved instanceof PsiClass c && myElement instanceof XmlAttributeValue a && a.getParent() instanceof XmlAttribute attr && attr.getName().equals("namespace") && MapperModel.isLegacy(MapperModel.root(a)) && !getValue().equals(c.getQualifiedName()) && !getValue().equals(c.getName()))return myElement;
            if(qualifiedClass && resolved instanceof PsiClass c){String old=getValue();int dot=old.lastIndexOf('.');if(dot>=0)newName=old.substring(0,dot+1)+newName;}
            return super.handleElementRename(newName);
        }
    }
}
