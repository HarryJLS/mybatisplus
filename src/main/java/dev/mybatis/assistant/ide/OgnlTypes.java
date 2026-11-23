package dev.mybatis.assistant.ide;

import com.intellij.psi.*;
import com.intellij.psi.util.*;
import dev.mybatis.assistant.core.OgnlSyntax;
import org.apache.ibatis.ognl.*;
import java.util.*;
import java.util.function.Consumer;

/** PSI-only expression types; no OGNL evaluation or business method calls. */
final class OgnlTypes {
    private OgnlTypes() {}
    static PsiType type(String expression,ParameterModel model,PsiElement context){
        if(expression==null || expression.isBlank())return null;
        try{return infer(OgnlSyntax.parse(expression),model,context,message->{},0);}
        catch(OgnlException|IllegalArgumentException e){return null;}
    }
    static PsiType infer(Node node,ParameterModel model,PsiElement context,Consumer<String> issue,int depth){
        if(depth>64)return null;
        if(node instanceof ASTConst constant){
            Object value=constant.getValue();
            if(value==null)return null;
            if(value instanceof String)return named(context,"java.lang.String");
            if(value instanceof Character)return PsiTypes.charType();
            if(value instanceof Boolean)return PsiTypes.booleanType();
            if(value instanceof Long)return PsiTypes.longType();
            if(value instanceof Number)return value instanceof Float?PsiTypes.floatType():value instanceof Double?PsiTypes.doubleType():PsiTypes.intType();
        }
        if(node instanceof ASTProperty property){
            String name=propertyName(property);
            if(name==null){for(int i=0;i<property.jjtGetNumChildren();i++)infer(property.jjtGetChild(i),model,context,issue,depth+1);return null;}
            var symbol=model.resolve(name);
            if(symbol==null && !model.dynamic() && !Set.of("_databaseId","_parameter","this","root").contains(name))issue.accept("找不到 OGNL 参数或属性: "+name);
            return symbol==null?null:symbol.type();
        }
        if(node instanceof ASTChain chain){
            PsiType current=null;String path="";
            for(int i=0;i<chain.jjtGetNumChildren();i++){
                Node child=chain.jjtGetChild(i);
                if(i==0){current=infer(child,model,context,issue,depth+1);path=child.toString();continue;}
                if(child instanceof ASTProperty property){
                    String name=propertyName(property);
                    if(property.isIndexedAccess()){
                        infer(property.jjtGetChild(0),model,context,issue,depth+1);
                        if(current!=null && !(current instanceof PsiArrayType) && !ParameterModel.isMap(current) && !InheritanceUtil.isInheritor(current,"java.util.List"))issue.accept("OGNL 索引访问需要数组、List 或 Map: "+path);
                        current=ParameterModel.indexType(current);
                        path+="["+property.jjtGetChild(0)+"]";
                    }else{
                        var symbol=ParameterModel.member(current,name);path+="."+name;
                        if(symbol==null && current!=null && !ParameterModel.isMap(current))issue.accept("找不到 OGNL 参数或属性: "+path);
                        current=symbol==null?null:symbol.type();
                    }
                }else if(child instanceof ASTMethod method){current=method(current,method,model,context,issue,depth+1,path);path+="."+method;}
                else {infer(child,model,context,issue,depth+1);current=null;}
            }
            return current;
        }
        if(node instanceof ASTTest && node.jjtGetNumChildren()==3){
            infer(node.jjtGetChild(0),model,context,issue,depth+1);
            PsiType left=infer(node.jjtGetChild(1),model,context,issue,depth+1),right=infer(node.jjtGetChild(2),model,context,issue,depth+1);
            return left!=null && left.equals(right)?left:left==null?right:right==null?left:null;
        }
        List<PsiType> children=new ArrayList<>();
        for(int i=0;i<node.jjtGetNumChildren();i++)children.add(infer(node.jjtGetChild(i),model,context,issue,depth+1));
        if(node instanceof ASTEq || node instanceof ASTNotEq){
            if(children.size()==2 && ((string(children.get(0)) && PsiTypes.charType().equals(children.get(1))) || (string(children.get(1)) && PsiTypes.charType().equals(children.get(0)))))issue.accept("OGNL 单字符单引号是 Character；比较 String 请使用双引号");
            return PsiTypes.booleanType();
        }
        if(node instanceof ASTAnd || node instanceof ASTOr || node instanceof ASTNot || node instanceof ASTGreater || node instanceof ASTGreaterEq || node instanceof ASTLess || node instanceof ASTLessEq || node instanceof ASTIn || node instanceof ASTNotIn)return PsiTypes.booleanType();
        if(node instanceof ASTAdd){if(children.stream().anyMatch(OgnlTypes::string))return named(context,"java.lang.String");return children.stream().filter(Objects::nonNull).findFirst().orElse(null);}
        return null;
    }
    private static String propertyName(ASTProperty property){return property.jjtGetNumChildren()==1 && property.jjtGetChild(0) instanceof ASTConst c && c.getValue() instanceof String s?s:null;}
    private static PsiType method(PsiType owner,ASTMethod node,ParameterModel model,PsiElement context,Consumer<String> issue,int depth,String path){
        List<PsiType> arguments=new ArrayList<>();for(int i=0;i<node.jjtGetNumChildren();i++)arguments.add(infer(node.jjtGetChild(i),model,context,issue,depth+1));
        if(!(owner instanceof PsiClassType clsType))return null;
        var generic=clsType.resolveGenerics();PsiClass cls=generic.getElement();if(cls==null)return null;
        List<PsiType> returns=new ArrayList<>();boolean named=false,arity=false;
        for(PsiMethod candidate:cls.findMethodsByName(node.getMethodName(),true)){
            if(!candidate.hasModifierProperty(PsiModifier.PUBLIC) || candidate.hasModifierProperty(PsiModifier.STATIC))continue;
            named=true;PsiParameter[] params=candidate.getParameterList().getParameters();
            if(!candidate.isVarArgs() && params.length!=arguments.size() || candidate.isVarArgs() && arguments.size()<params.length-1)continue;
            arity=true;PsiSubstitutor sub=TypeConversionUtil.getSuperClassSubstitutor(candidate.getContainingClass(),cls,generic.getSubstitutor());boolean matches=true;
            for(int i=0;i<arguments.size();i++){
                PsiType expected=sub.substitute(params[Math.min(i,params.length-1)].getType());
                if(candidate.isVarArgs() && i>=params.length-1 && expected instanceof PsiArrayType array)expected=array.getComponentType();
                PsiType actual=arguments.get(i);
                // OGNL performs numeric and String coercion. Do not reject those Java conversions.
                if(actual!=null && expected!=null && !TypeConversionUtil.isAssignable(expected,actual) && !(numeric(expected) && numeric(actual)) && !string(expected) && !string(actual))matches=false;
            }
            if(matches)returns.add(sub.substitute(candidate.getReturnType()));
        }
        if(!named || !arity)issue.accept("找不到 OGNL 方法或参数数量不符: "+path+"."+node.getMethodName()+"()");
        else if(returns.isEmpty())issue.accept("OGNL 方法参数类型不兼容: "+path+"."+node.getMethodName()+"()");
        if(returns.isEmpty())return null;PsiType first=returns.getFirst();return returns.stream().allMatch(t->Objects.equals(t,first))?first:null;
    }
    private static boolean numeric(PsiType type){return type!=null && (Set.of("byte","short","int","long","float","double","java.lang.Byte","java.lang.Short","java.lang.Integer","java.lang.Long","java.lang.Float","java.lang.Double").contains(type.getCanonicalText()) || InheritanceUtil.isInheritor(type,"java.lang.Number"));}
    private static boolean string(PsiType type){return type!=null && type.equalsToText("java.lang.String");}
    static PsiType named(PsiElement context,String name){return JavaPsiFacade.getElementFactory(context.getProject()).createTypeByFQClassName(name,context.getResolveScope());}
}
