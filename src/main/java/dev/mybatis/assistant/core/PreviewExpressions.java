package dev.mybatis.assistant.core;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.ognl.*;
import org.apache.ibatis.reflection.Reflector;
import org.apache.ibatis.scripting.xmltags.OgnlClassResolver;

/** One render owns this bounded AST cache; no expressions survive in a static map. */
final class PreviewExpressions {
    private static final int MAX_ENTRIES=256,MAX_CHARS=262144;
    private final LinkedHashMap<String,Node> nodes=new LinkedHashMap<>(16,0.75f,true);
    private final Runnable cancellation;
    private int characters;
    PreviewExpressions(Runnable cancellation){this.cancellation=cancellation;}
    void checkCanceled(){cancellation.run();}
    Node parse(String text)throws OgnlException{
        checkCanceled();Node node=nodes.get(text);if(node!=null)return node;
        node=OgnlSyntax.parse(text);
        while(!nodes.isEmpty() && (nodes.size()>=MAX_ENTRIES || characters+text.length()>MAX_CHARS)){
            String oldest=nodes.keySet().iterator().next();characters-=oldest.length();nodes.remove(oldest);
        }
        nodes.put(text,node);characters+=text.length();return node;
    }
    Object value(String expression,Object root){
        try{
            OgnlContext context=Ognl.createDefaultContext(root,MEMBER_ACCESS,new OgnlClassResolver(),null);
            return Ognl.getValue(parse(expression),context,root);
        }catch(OgnlException e){throw new BuilderException("Error evaluating expression '"+expression+"'. Cause: "+e,e);}
    }
    boolean bool(String expression,Object root){
        Object value=value(expression,root);
        if(value instanceof Boolean flag)return flag;
        if(value instanceof Number number)return new BigDecimal(String.valueOf(number)).compareTo(BigDecimal.ZERO)!=0;
        return value!=null;
    }
    Iterable<?> evaluateIterable(String expression,Object root,boolean nullable){
        Object value=value(expression,root);
        if(value==null){if(nullable)return null;throw new BuilderException("The expression '"+expression+"' evaluated to a null value.");}
        if(value instanceof Iterable<?> iterable)return iterable;
        if(value instanceof Map<?,?> map)return map.entrySet();
        if(value.getClass().isArray()){
            List<Object> result=new ArrayList<>();for(int i=0;i<Array.getLength(value);i++)result.add(Array.get(value,i));return result;
        }
        throw new BuilderException("Error evaluating expression '"+expression+"'. Return value ("+value+") was not iterable.");
    }
    // Match MyBatis member accessibility; PreviewSafety rejects reflection and non-whitelisted calls first.
    private static final MemberAccess MEMBER_ACCESS=new MemberAccess(){
        @Override public boolean isAccessible(OgnlContext context,Object target,Member member,String property){return Reflector.canControlMemberAccessible();}
        @Override public Object setup(OgnlContext context,Object target,Member member,String property){
            if(isAccessible(context,target,member,property)){
                AccessibleObject accessible=(AccessibleObject)member;
                if(!accessible.isAccessible()){accessible.setAccessible(true);return Boolean.FALSE;}
            }return null;
        }
        @Override public void restore(OgnlContext context,Object target,Member member,String property,Object state){}
    };
}
