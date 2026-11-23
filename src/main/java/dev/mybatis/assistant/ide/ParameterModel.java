package dev.mybatis.assistant.ide;

import com.intellij.psi.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
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
        private Symbol(PsiType type,Symbol original){this.pointer=original.pointer;this.type=type;}
        public PsiElement declaration(){return pointer==null?null:pointer.getElement();}
        public PsiType type(){return type;}
    }
    private final Map<String,Symbol> symbols=new LinkedHashMap<>();
    private final Map<String,Symbol> readOnlySymbols=Collections.unmodifiableMap(symbols);
    private boolean dynamic;
    private PsiType legacyParameterType;
    private PsiType rootMapType;
    private static final Pattern SIMPLE=Pattern.compile("(java\\.lang\\.(String|Integer|Long|Short|Byte|Double|Float|Boolean|Character)|java\\.math\\..*|java\\.time\\..*|java\\.util\\.Date)");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> CONTEXT_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.context");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> METHOD_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.method");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> LEGACY_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.ibatis");
    private static final com.intellij.openapi.util.Key<CachedValue<ParameterModel>> SPECIALIZED_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.binding.specialized");
    private static final com.intellij.openapi.util.Key<CachedValue<Map<String,Symbol>>> PROPERTIES_CACHE=com.intellij.openapi.util.Key.create("mybatis.assistant.class.properties");
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
            if(type==null || isMap(type)){model.dynamic=true;model.rootMapType=type;}
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
            ParameterModel bindings=statement!=null && MapperModel.isLegacy(MapperModel.root(statement))?legacyBase(statement):statement==null?fragmentBase(context):specializedBase(statement);
            return CachedValueProvider.Result.create(scoped(bindings,context),context.getContainingFile(),PsiModificationTracker.MODIFICATION_COUNT,context.getProject().getService(AssistantSettings.class),MapperUsageIndex.tracker(context.getProject()));
        },false);
    }
    private static ParameterModel fragmentBase(PsiElement context){
        XmlTag fragment=PsiTreeUtil.getParentOfType(context,XmlTag.class,false);while(fragment!=null && !fragment.getName().equals("sql"))fragment=fragment.getParentTag();
        if(fragment==null)return base(null);XmlTag root=MapperModel.root(fragment);if(root==null)return base(null);
        ParameterModel result=new ParameterModel();int[] steps={0};boolean found=false;
        Set<XmlTag> roots=new LinkedHashSet<>(List.of(root));String namespace=root.getAttributeValue("namespace");if(namespace!=null)roots.addAll(MapperUsageIndex.roots(fragment,"sql",namespace+"."+fragment.getAttributeValue("id")));
        for(XmlTag candidate:roots)for(XmlTag statement:candidate.getSubTags())if(MapperModel.isStatement(statement) && uses(statement,fragment,new HashSet<>(),0,steps)){
            found=true;ParameterModel model=specializedBase(statement);result.dynamic|=model.dynamic;
            for(var entry:model.symbols.entrySet()){Symbol old=result.symbols.get(entry.getKey());result.symbols.put(entry.getKey(),old==null?entry.getValue():Objects.equals(old.type(),entry.getValue().type())?old:new Symbol(old.declaration(),null));}
        }
        result.dynamic|=!found;return result;
    }
    static boolean uses(XmlTag tag,XmlTag fragment,Set<XmlTag> seen,int depth,int[] steps){
        if(depth>16 || ++steps[0]>2048 || !seen.add(tag))return false;
        for(XmlTag child:tag.getSubTags()){
            if(child.getName().equals("include")){XmlTag target=MapperModel.definition(child,child.getAttributeValue("refid"),"sql");if(target==fragment || target!=null && uses(target,fragment,seen,depth+1,steps))return true;}
            else if(uses(child,fragment,seen,depth+1,steps))return true;
        }return false;
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
                else if(simple(type) || isMap(type)){model.dynamic=true;if(isMap(type))model.rootMapType=type;}
                else {model.symbols.remove(p.getName());model.symbols.remove("arg"+i);model.symbols.putAll(properties(type));}
            }
        }
        return model;
    }
    private static ParameterModel scoped(ParameterModel base,PsiElement context){
            List<XmlTag> ancestors=new ArrayList<>();XmlTag t=PsiTreeUtil.getParentOfType(context,XmlTag.class,false);
            while(t!=null){ancestors.add(t);t=t.getParentTag();}Collections.reverse(ancestors);
            if(ancestors.stream().noneMatch(parent->!Set.of("mapper","sqlMap").contains(parent.getName()) && scopedNodes(parent,0,new int[]{0})))return base;
            ParameterModel model=new ParameterModel();model.symbols.putAll(base.symbols);model.dynamic=base.dynamic;model.legacyParameterType=base.legacyParameterType;model.rootMapType=base.rootMapType;
            for(XmlTag parent:ancestors){
                if(Set.of("mapper","sqlMap").contains(parent.getName()))continue;
                // A bind becomes visible after its declaration, and can refer to earlier binds.
                for(XmlTag child:parent.getSubTags()){
                    if(child.getTextRange().getEndOffset()>context.getTextRange().getStartOffset())break;
                    earlierBinds(child,model,MapperModel.root(context),false,new HashSet<>(),0,new int[]{0});
                }
                if("foreach".equals(parent.getName()) && !(context instanceof com.intellij.psi.xml.XmlAttribute a && a.getParent()==parent) && !(context instanceof com.intellij.psi.xml.XmlAttributeValue a && a.getParent().getParent()==parent)){
                    Symbol collection=model.resolve(parent.getAttributeValue("collection"));
                    if(collection==null || Objects.requireNonNullElse(parent.getAttributeValue("collection"),"").contains("(")){PsiType inferred=OgnlTypes.type(parent.getAttributeValue("collection"),model,parent);collection=inferred==null?null:new Symbol(parent,inferred);}
                    PsiType type=collection==null?null:elementType(collection.type());
                    model.symbols.put(Objects.requireNonNullElse(parent.getAttributeValue("item"),"item"),new Symbol(parent.getAttribute("item")==null?parent:parent.getAttribute("item").getValueElement(),type));
                    PsiType index=collection!=null && isMap(collection.type())?typeArgument(collection.type(),"java.util.Map",0):PsiTypes.intType();
                    model.symbols.put(Objects.requireNonNullElse(parent.getAttributeValue("index"),"index"),new Symbol(parent.getAttribute("index")==null?parent:parent.getAttribute("index").getValueElement(),index));
                }
                if("iterate".equals(parent.getName()) && parent.getAttributeValue("property")==null){
                    model.symbols.put("[]",new Symbol(parent,MapperModel.entityType(base.legacyParameterType)));
                }
            }
        return model;
    }
    private static boolean scopedNodes(XmlTag tag,int depth,int[] steps){
        if(depth>16 || ++steps[0]>512)return true;
        if(Set.of("foreach","iterate","bind","include").contains(tag.getName()))return true;
        for(XmlTag child:tag.getSubTags())if(scopedNodes(child,depth+1,steps))return true;return false;
    }
    private static void earlierBinds(XmlTag tag,ParameterModel model,XmlTag callingRoot,boolean conditional,Set<XmlTag> stack,int depth,int[] steps){
        if(depth>16 || ++steps[0]>512 || !stack.add(tag)){model.dynamic=true;return;}
        try{
            if(tag.getName().equals("bind")){
                String name=tag.getAttributeValue("name");XmlAttribute attr=tag.getAttribute("name");
                if(name!=null && attr.getValueElement()!=null)model.symbols.put(name,new Symbol(attr.getValueElement(),conditional?null:OgnlTypes.type(tag.getAttributeValue("value"),model,tag)));
                return;
            }
            if(tag.getName().equals("include")){
                XmlTag target=MapperModel.definition(callingRoot,tag.getAttributeValue("refid"),"sql");if(target!=null)earlierBinds(target,model,callingRoot,conditional,stack,depth+1,steps);return;
            }
            boolean uncertain=conditional || Set.of("if","choose","when","otherwise","foreach").contains(tag.getName());
            for(XmlTag child:tag.getSubTags())earlierBinds(child,model,callingRoot,uncertain,stack,depth+1,steps);
        }finally{stack.remove(tag);}
    }
    public static String paramName(PsiParameter p){
        PsiAnnotation a=p.getAnnotation("org.apache.ibatis.annotations.Param");if(a==null)return null;
        PsiAnnotationMemberValue value=a.findAttributeValue("value");return value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s?s:null;
    }
    public Map<String,Symbol> symbols(){return readOnlySymbols;}
    public boolean dynamic(){return dynamic;}
    public Symbol resolve(String path){
        if(path==null)return null;
        if(path.startsWith("_parameter.") && !symbols.containsKey("_parameter"))path=path.substring(11);
        List<String> parts=pathParts(path);if(parts.isEmpty())return null;
        Symbol s=null;
        for(int i=0;i<parts.size();i++){
            String part=parts.get(i);int open=part.indexOf('[');String name=open<0?part:part.substring(0,open);
            if(i==0){s=symbols.get(part.startsWith("[]")?"[]":name);if(s==null && rootMapType!=null)s=member(rootMapType,name);}
            else if(s!=null){Symbol next=member(s.type(),name);s=next==null && s.type()==null?new Symbol(s.declaration(),null):next;}
            if(s==null)return null;
            if(!part.startsWith("[]"))for(int j=0;j<part.length();j++)if(part.charAt(j)=='['){int end=part.indexOf(']',j);if(end<0)return null;PsiType indexed=indexType(s.type());if(indexed==null && s.type()!=null && !isMap(s.type()))return null;s=new Symbol(s.declaration(),indexed);j=end;}
        }
        return s;
    }
    private static List<String> pathParts(String path){
        if(path.length()>4096)return List.of();List<String> result=new ArrayList<>();int start=0,depth=0;char quote=0;
        for(int i=0;i<path.length();i++){char c=path.charAt(i);if(quote!=0){if(c=='\\')i++;else if(c==quote)quote=0;}else if(c=='\'' || c=='"')quote=c;else if(c=='[')depth++;else if(c==']')depth--;else if(c=='.' && depth==0){result.add(path.substring(start,i));start=i+1;}}
        result.add(path.substring(start));return result;
    }
    static Symbol member(PsiType type,String name){
        if(name==null)return null;
        Symbol known=properties(type).get(name);if(known!=null)return known;
        if(isMap(type))return new Symbol(null,bound(typeArgument(type,"java.util.Map",1)));
        return null;
    }
    static PsiType indexType(PsiType type){
        if(isMap(type))return bound(typeArgument(type,"java.util.Map",1));
        if(type instanceof PsiArrayType array)return bound(array.getComponentType());
        if(type instanceof PsiClassType ct && ct.rawType().equalsToText("java.util.List") || type!=null && InheritanceUtil.isInheritor(type,"java.util.List"))return bound(typeArgument(type,"java.util.List",0));
        return null;
    }
    static PsiType elementType(PsiType type){
        if(type instanceof PsiArrayType array)return bound(array.getComponentType());
        return isMap(type)?bound(typeArgument(type,"java.util.Map",1)):bound(typeArgument(type,"java.lang.Iterable",0));
    }
    private static PsiType bound(PsiType type){if(type instanceof PsiWildcardType wildcard)return wildcard.isExtends()?wildcard.getBound():null;if(type instanceof PsiCapturedWildcardType captured)return captured.getUpperBound();return type;}
    static PsiType typeArgument(PsiType type,String base,int index){
        if(!(type instanceof PsiClassType ct))return null;var resolved=ct.resolveGenerics();PsiClass cls=resolved.getElement();if(cls==null)return ct.getParameters().length>index?ct.getParameters()[index]:null;
        PsiClass target=JavaPsiFacade.getInstance(cls.getProject()).findClass(base,cls.getResolveScope());if(target==null || target.getTypeParameters().length<=index)return null;
        if(!target.isEquivalentTo(cls) && !InheritanceUtil.isInheritor(cls,base))return null;
        return TypeConversionUtil.getSuperClassSubstitutor(target,cls,resolved.getSubstitutor()).substitute(target.getTypeParameters()[index]);
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
        if(cls==null){
            if(Set.of("java.util.List","java.util.Set","java.util.Collection").contains(ct.rawType().getCanonicalText()))
                return Map.of("size",new Symbol(null,PsiTypes.intType()),"isEmpty",new Symbol(null,PsiTypes.booleanType()));
            return Map.of();
        }
        Map<String,Symbol> members=CachedValuesManager.getManager(cls.getProject()).getCachedValue(cls,PROPERTIES_CACHE,()->
            CachedValueProvider.Result.create(classProperties(cls),cls.getContainingFile(),ModelDependencies.types(cls.getProject()),ProjectRootModificationTracker.getInstance(cls.getProject())),false);
        if(generic.getSubstitutor().getSubstitutionMap().isEmpty())return members;
        Map<String,Symbol> result=new LinkedHashMap<>();
        members.forEach((name,symbol)->result.put(name,new Symbol(generic.getSubstitutor().substitute(symbol.type()),symbol)));
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,Symbol> classProperties(PsiClass cls){
        Map<String,Symbol> result=new LinkedHashMap<>();
        if(Set.of("java.util.List","java.util.Set","java.util.Collection").contains(Objects.requireNonNullElse(cls.getQualifiedName(),"")) || InheritanceUtil.isInheritor(cls,"java.util.Collection")){
            result.put("size",new Symbol(cls,PsiTypes.intType()));result.put("isEmpty",new Symbol(cls,PsiTypes.booleanType()));
        }
        if("java.util.Map".equals(cls.getQualifiedName()) || InheritanceUtil.isInheritor(cls,"java.util.Map")){
            result.put("size",new Symbol(cls,PsiTypes.intType()));result.put("isEmpty",new Symbol(cls,PsiTypes.booleanType()));
            for(String name:List.of("values","keySet"))for(PsiMethod method:cls.findMethodsByName(name,true))if(method.getParameterList().getParametersCount()==0){PsiSubstitutor sub=TypeConversionUtil.getSuperClassSubstitutor(method.getContainingClass(),cls,PsiSubstitutor.EMPTY);Symbol symbol=new Symbol(method,sub.substitute(method.getReturnType()));result.put(name,symbol);if(name.equals("keySet"))result.put("keys",symbol);break;}
        }
        for(PsiField f:cls.getAllFields())if(!f.hasModifierProperty(PsiModifier.STATIC)){
            ProgressManager.checkCanceled();
            PsiSubstitutor sub=f.getContainingClass()==null?PsiSubstitutor.EMPTY:TypeConversionUtil.getSuperClassSubstitutor(f.getContainingClass(),cls,PsiSubstitutor.EMPTY);
            result.putIfAbsent(f.getName(),new Symbol(f,sub.substitute(f.getType())));
        }
        for(PsiMethod m:cls.getAllMethods()){
            ProgressManager.checkCanceled();
            if(m.hasModifierProperty(PsiModifier.STATIC) || m.getParameterList().getParametersCount()!=0 || m.getReturnType()==null)continue;
            String name=m.getName(),property=null;
            if(name.startsWith("get") && name.length()>3 && !name.equals("getClass"))property=decap(name.substring(3));
            else if(name.startsWith("is") && name.length()>2)property=decap(name.substring(2));
            if(property!=null){PsiSubstitutor sub=m.getContainingClass()==null?PsiSubstitutor.EMPTY:TypeConversionUtil.getSuperClassSubstitutor(m.getContainingClass(),cls,PsiSubstitutor.EMPTY);result.putIfAbsent(property,new Symbol(m,sub.substitute(m.getReturnType())));}
        }
        return Collections.unmodifiableMap(result);
    }
    private static String decap(String s){return s.length()>1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))?s:Character.toLowerCase(s.charAt(0))+s.substring(1);}
}
