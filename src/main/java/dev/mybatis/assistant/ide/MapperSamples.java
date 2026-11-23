package dev.mybatis.assistant.ide;

import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import java.util.*;
import com.google.gson.GsonBuilder;

final class MapperSamples {
    private MapperSamples() {}
    static String json(PsiMethod method){
        Map<String,Object> values=new LinkedHashMap<>();int[] count={0};
        ParameterModel.of(method,null).symbols().forEach((name,symbol)->{if(!name.equals("_parameter"))values.put(name,value(symbol.type(),new HashSet<>(),0,count));});
        return new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(values);
    }
    private static Object value(PsiType type,Set<String> stack,int depth,int[] count){
        if(type==null || depth>4 || ++count[0]>256 || !stack.add(type.getCanonicalText()))return null;
        try{
            String name=type.getCanonicalText();if(name.equals("boolean") || name.equals("java.lang.Boolean"))return true;
            if(name.equals("java.lang.String") || name.equals("char") || name.equals("java.lang.Character"))return "sample";
            if(type instanceof PsiPrimitiveType || name.matches("java\\.lang\\.(Byte|Short|Integer|Long|Float|Double)") || name.startsWith("java.math."))return 1;
            if(type instanceof PsiArrayType || InheritanceUtil.isInheritor(type,"java.lang.Iterable"))return Arrays.asList(value(ParameterModel.elementType(type),stack,depth+1,count));
            if(ParameterModel.isMap(type)){Map<String,Object> map=new LinkedHashMap<>();map.put("sample",value(ParameterModel.indexType(type),stack,depth+1,count));return map;}
            if(name.startsWith("java.time.") || name.equals("java.util.Date"))return "2026-01-01";
            Map<String,Object> bean=new LinkedHashMap<>();for(var property:ParameterModel.properties(type).entrySet())bean.put(property.getKey(),value(property.getValue().type(),stack,depth+1,count));return bean;
        }finally{stack.remove(type.getCanonicalText());}
    }
}
