package dev.mybatis.assistant.ide;

import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.*;
import java.util.*;
import java.util.regex.Pattern;

/** Semantic bindings come from PSI, so inherited fields and generic getter types are retained. */
public final class ParameterModel {
    public static final class Symbol {
        private final SmartPsiElementPointer<PsiElement> pointer;
        private final PsiType type;
        public Symbol(PsiElement declaration,PsiType type){this.pointer=declaration==null?null:SmartPointerManager.getInstance(declaration.getProject()).createSmartPsiElementPointer(declaration);this.type=type;}
        public PsiElement declaration(){return pointer==null?null:pointer.getElement();}
        public PsiType type(){return type;}
    }
    private final Map<String,Symbol> symbols=new LinkedHashMap<>();
    private final Map<String,Symbol> readOnlySymbols=Collections.unmodifiableMap(symbols);
    private boolean dynamic;
    private PsiType legacyParameterType;
    private static final Pattern SIMPLE=Pattern.compile("(java\\.lang\\.(String|Integer|Long|Short|Byte|Double|Float|Boolean|Character)|java\\.math\\..*|java\\.time\\..*|java\\.util\\.Date)");
    private static final Pattern INDEX=Pattern.compile("\\[[^]]*]");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> CONTEXT_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.context");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> METHOD_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.method");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> LEGACY_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.ibatis");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> SPECIALIZED_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.specialized");
    private static ParameterModel specializedBase(XmlTag statement){
        return CachedValuesManager.getManager(statement.getProject()).getCachedValue(statement,SPECIALIZED_CACHE,()->{
            PsiMethod method=MapperModel.method(statement);PsiClass mapper=MapperModel.mapperClass(MapperModel.root(statement));
            PsiClass declaring=method==null?null:method.getContainingClass();
            ParameterModel model=declaring!=null && mapper!=null && !declaring.isEquivalentTo(mapper)
                ?build(method,TypeConversionUtil.getSuperClassSubstitutor(declaring,mapper,PsiSubstitutor.EMPTY)):base(method);
            return CachedValueProvider.Result.create(model,statement.getContainingFile(),PsiModificationTracker.MODIFICATION_COUNT,statement.getProject().getService(AssistantSettings.class));
        },false);
    }
    private static ParameterModel legacyBase(XmlTag statement){
        return CachedValuesManager.getManager(statement.getProject()).getCachedValue(statement,LEGACY_CACHE,()->{
            ParameterModel model=new ParameterModel();String declared=statement.getAttributeValue("parameterClass");
            if(declared==null){XmlTag map=MapperModel.definition(statement,statement.getAttributeValue("parameterMap"),"parameterMap");if(map!=null)declared=map.getAttributeValue("class");}
            PsiType type=declared==null?null:MappingTypes.named(statement,declared);
            PsiMethod method=MapperModel.method(statement);PsiElement declaration=statement;
            if(type==null && declared==null && method!=null && method.getParameterList().getParametersCount()==1){PsiParameter parameter=method.getParameterList().getParameters()[0];type=parameter.getType();declaration=parameter;}
            model.legacyParameterType=type;
            if(type==null || isMap(type)){model.dynamic=true;}
            else if(simple(type)){model.dynamic=true;model.symbols.put("value",new Symbol(declaration,type));}
            else if(type instanceof PsiArrayType || InheritanceUtil.isInheritor(type,"java.lang.Iterable")){model.dynamic=true;}
            else model.symbols.putAll(properties(type));
            return CachedValueProvider.Result.create(model,statement.getContainingFile(),PsiModificationTracker.MODIFICATION_COUNT,statement.getProject().getService(AssistantSettings.class));
        },false);
    }
    private static ParameterModel base(PsiMethod method){
        if(method==null){ParameterModel unknown=new ParameterModel();unknown.dynamic=true;return unknown;}
        return CachedValuesManager.getManager(method.getProject()).getCachedValue(method,METHOD_CACHE,()->CachedValueProvider.Result.create(
                build(method),method.getContainingFile(),PsiModificationTracker.MODIFICATION_COUNT,method.getProject().getService(AssistantSettings.class)),false);
    }
    public static ParameterModel of(PsiMethod method,PsiElement context){
        if(context==null)return base(method);
        // The provider may retain only its owning XML PSI; resolve the Java method when recomputing.
        return CachedValuesManager.getManager(context.getProject()).getCachedValue(context,CONTEXT_CACHE,()->{
            XmlTag statement=MapperModel.statement(context);
            PsiMethod resolved=statement==null?null:MapperModel.method(statement);
            ParameterModel bindings=statement!=null && MapperModel.isLegacy(MapperModel.root(statement))?legacyBase(statement):statement==null?base(resolved):specializedBase(statement);
            return CachedValueProvider.Result.create(scoped(bindings,context),context.getContainingFile(),PsiModificationTracker.MODIFICATION_COUNT,context.getProject().getService(AssistantSettings.class));
        },false);
    }
    private static ParameterModel build(PsiMethod method){
        return build(method,PsiSubstitutor.EMPTY);
    }
    private static ParameterModel build(PsiMethod method,PsiSubstitutor substitutor){
        ParameterModel model=new ParameterModel();if(method==null){model.dynamic=true;return model;}
        PsiParameter[] params=method.getParameterList().getParameters();
        List<PsiParameter> binding=new ArrayList<>();
        for(PsiParameter p:params)if(!Set.of("org.apache.ibatis.session.RowBounds","org.apache.ibatis.session.ResultHandler").contains(p.getType().getCanonicalText()))binding.add(p);
        boolean named=binding.stream().anyMatch(p->p.hasAnnotation("org.apache.ibatis.annotations.Param"));
        for(int i=0;i<binding.size();i++){
            PsiParameter p=binding.get(i);PsiType type=substitutor.substitute(p.getType());Symbol s=new Symbol(p,type);String annotation=paramName(p);
            if(annotation!=null)model.symbols.put(annotation,s);
            else if(AssistantSettings.get(method.getProject()).useActualParamName)model.symbols.put(p.getName(),s);
            else model.symbols.put("arg"+i,s);
            if(binding.size()>1 || named)model.symbols.putIfAbsent("param"+(i+1),s);
            if(binding.size()==1 && !named){
                model.symbols.put("_parameter",s);
                if(type instanceof PsiArrayType)model.symbols.put("array",s);
                else if(InheritanceUtil.isInheritor(type,"java.util.Collection")){model.symbols.put("collection",s);if(InheritanceUtil.isInheritor(type,"java.util.List"))model.symbols.put("list",s);}
                else if(simple(type) || isMap(type))model.dynamic=true;
                else {model.symbols.remove(p.getName());model.symbols.remove("arg"+i);model.symbols.putAll(properties(type));}
            }
        }
        return model;
    }
    private static ParameterModel scoped(ParameterModel base,PsiElement context){
            List<XmlTag> ancestors=new ArrayList<>();XmlTag t=PsiTreeUtil.getParentOfType(context,XmlTag.class,false);
            while(t!=null){ancestors.add(t);t=t.getParentTag();}Collections.reverse(ancestors);
            if(ancestors.stream().noneMatch(parent->"foreach".equals(parent.getName()) || "iterate".equals(parent.getName()) || parent.findSubTags("bind").length>0))return base;
            ParameterModel model=new ParameterModel();model.symbols.putAll(base.symbols);model.dynamic=base.dynamic;model.legacyParameterType=base.legacyParameterType;
            for(XmlTag parent:ancestors){
                // Bind variables are statement-scoped; do not descend through arbitrary child tags.
                for(XmlTag child:parent.findSubTags("bind")){String name=child.getAttributeValue("name");if(name!=null)model.symbols.put(name,new Symbol(child.getAttribute("name").getValueElement(),null));}
                if("foreach".equals(parent.getName())){
                    Symbol collection=model.resolve(parent.getAttributeValue("collection"));
                    PsiType type=collection==null?null:MapperModel.entityType(collection.type());
                    if(collection!=null && isMap(collection.type()) && collection.type() instanceof PsiClassType map && map.getParameters().length==2)type=map.getParameters()[1];
                    model.symbols.put(Objects.requireNonNullElse(parent.getAttributeValue("item"),"item"),new Symbol(parent.getAttribute("item")==null?parent:parent.getAttribute("item").getValueElement(),type));
                    model.symbols.put(Objects.requireNonNullElse(parent.getAttributeValue("index"),"index"),new Symbol(parent.getAttribute("index")==null?parent:parent.getAttribute("index").getValueElement(),PsiTypes.intType()));
                }
                if("iterate".equals(parent.getName()) && parent.getAttributeValue("property")==null){
                    model.symbols.put("[]",new Symbol(parent,MapperModel.entityType(base.legacyParameterType)));
                }
            }
        return model;
    }
    public static String paramName(PsiParameter p){
        PsiAnnotation a=p.getAnnotation("org.apache.ibatis.annotations.Param");if(a==null)return null;
        PsiAnnotationMemberValue value=a.findAttributeValue("value");return value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s?s:null;
    }
    public Map<String,Symbol> symbols(){return readOnlySymbols;}
    public boolean dynamic(){return dynamic;}
    public Symbol resolve(String path){
        if(path==null)return null;
        String[] parts=path.split("\\.");
        Symbol s=symbols.get(parts[0].startsWith("[]")?"[]":INDEX.matcher(parts[0]).replaceAll(""));
        if(s!=null && parts[0].contains("["))s=new Symbol(s.declaration(),MapperModel.entityType(s.type()));
        for(int i=1;s!=null && i<parts.length;i++){
            if(isMap(s.type()) || s.type()==null)return new Symbol(s.declaration(),null);
            s=properties(s.type()).get(INDEX.matcher(parts[i]).replaceAll(""));
            if(s!=null && parts[i].contains("["))s=new Symbol(s.declaration(),MapperModel.entityType(s.type()));
        }return s;
    }
    public Map<String,Symbol> complete(String path){
        int dot=path.lastIndexOf('.');if(dot<0)return symbols;
        Symbol s=resolve(path.substring(0,dot));return s==null?Map.of():properties(s.type());
    }
    public static boolean simple(PsiType type){return type instanceof PsiPrimitiveType || type!=null && SIMPLE.matcher(type.getCanonicalText()).matches();}
    public static boolean isMap(PsiType type){return type!=null && InheritanceUtil.isInheritor(type,"java.util.Map");}
    public static Map<String,Symbol> properties(PsiType type){
        if(type instanceof PsiArrayType)return Map.of("length",new Symbol(null,PsiTypes.intType()));
        if(!(type instanceof PsiClassType ct))return Map.of();var generic=ct.resolveGenerics();PsiClass cls=generic.getElement();
        Map<String,Symbol> result=new LinkedHashMap<>();
        if(Set.of("java.util.List","java.util.Set","java.util.Collection").contains(ct.rawType().getCanonicalText()) || InheritanceUtil.isInheritor(type,"java.util.Collection")){
            result.put("size",new Symbol(cls,PsiTypes.intType()));result.put("isEmpty",new Symbol(cls,PsiTypes.booleanType()));
        }
        if(cls==null)return result;
        for(PsiField f:cls.getAllFields())if(!f.hasModifierProperty(PsiModifier.STATIC)){
            PsiSubstitutor sub=f.getContainingClass()==null?generic.getSubstitutor():TypeConversionUtil.getSuperClassSubstitutor(f.getContainingClass(),cls,generic.getSubstitutor());
            result.putIfAbsent(f.getName(),new Symbol(f,sub.substitute(f.getType())));
        }
        for(PsiMethod m:cls.getAllMethods()){
            if(m.hasModifierProperty(PsiModifier.STATIC) || m.getParameterList().getParametersCount()!=0 || m.getReturnType()==null)continue;
            String name=m.getName(),property=null;
            if(name.startsWith("get") && name.length()>3 && !name.equals("getClass"))property=decap(name.substring(3));
            else if(name.startsWith("is") && name.length()>2)property=decap(name.substring(2));
            if(property!=null){PsiSubstitutor sub=m.getContainingClass()==null?generic.getSubstitutor():TypeConversionUtil.getSuperClassSubstitutor(m.getContainingClass(),cls,generic.getSubstitutor());result.putIfAbsent(property,new Symbol(m,sub.substitute(m.getReturnType())));}
        }
        return result;
    }
    private static String decap(String s){return s.length()>1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))?s:Character.toLowerCase(s.charAt(0))+s.substring(1);}
}
