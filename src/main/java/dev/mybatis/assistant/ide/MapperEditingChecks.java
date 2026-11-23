package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.*;
import java.util.*;

final class MapperEditingChecks {
    private MapperEditingChecks() {}
    private static final Set<String> DYNAMIC=Set.of("if","where","set","trim","choose","when","otherwise","foreach","bind","include","selectKey","property");
    static void tag(XmlTag tag,ProblemsHolder holder){
        XmlTag root=MapperModel.root(tag);if(root==null || MapperModel.isLegacy(root))return;
        XmlTag parent=tag.getParentTag();String name=tag.getName();
        if(parent!=null && (MapperModel.statement(tag)!=null || ancestorSql(tag)) && tag!=MapperModel.statement(tag) && !name.equals("sql")){
            if(!DYNAMIC.contains(name))holder.registerProblem(tag,"未知 MyBatis 动态标签: "+name);
            if(Set.of("when","otherwise").contains(name) && !parent.getName().equals("choose"))holder.registerProblem(tag,name+" 必须位于 choose 内");
            if(name.equals("choose")){
                if(tag.findSubTags("when").length==0)holder.registerProblem(tag,"choose 至少需要一个 when");
                if(tag.findSubTags("otherwise").length>1)holder.registerProblem(tag,"choose 只能有一个 otherwise");
                for(XmlTag child:tag.getSubTags())if(!Set.of("when","otherwise").contains(child.getName()))holder.registerProblem(child,"choose 只允许 when / otherwise 子标签");
            }
            if(Set.of("if","when").contains(name))required(tag,"test",holder);
            if(name.equals("bind")){required(tag,"name",holder);required(tag,"value",holder);identifier(tag,"name",holder);}
            if(name.equals("include"))required(tag,"refid",holder);
            if(name.equals("foreach")){
                required(tag,"collection",holder);identifier(tag,"item",holder);identifier(tag,"index",holder);
                if(tag.getAttributeValue("item")!=null && tag.getAttributeValue("item").equals(tag.getAttributeValue("index")))problem(tag,"index",holder,"foreach item 和 index 不能同名");
                var attr=tag.getAttribute("collection");
                if(attr!=null){PsiType type=OgnlTypes.type(attr.getValue(),ParameterModel.of(null,attr),attr);if(type!=null && !(type instanceof PsiArrayType) && !ParameterModel.isMap(type) && !InheritanceUtil.isInheritor(type,"java.lang.Iterable"))problem(tag,"collection",holder,"foreach collection 必须是数组、Iterable 或 Map");}
            }
        }
        for(String attribute:List.of("type","resultType","parameterType","ofType","javaType")){
            String type=tag.getAttributeValue(attribute);
            if(type!=null && !type.contains("${") && MapperModel.typeClass(tag,type)==null && !Set.of("boolean","byte","short","int","long","float","double","char").contains(type) && !TypeAliases.names(tag).containsKey(type))problem(tag,attribute,holder,"找不到映射类型或别名: "+type);
        }
    }
    private static boolean ancestorSql(XmlTag tag){for(XmlTag parent=tag.getParentTag();parent!=null;parent=parent.getParentTag())if(parent.getName().equals("sql"))return true;return false;}
    private static void required(XmlTag tag,String name,ProblemsHolder holder){if(tag.getAttributeValue(name)==null || tag.getAttributeValue(name).isBlank())holder.registerProblem(tag,tag.getName()+" 缺少 "+name);}
    private static void identifier(XmlTag tag,String name,ProblemsHolder holder){String value=tag.getAttributeValue(name);if(value!=null && !value.matches("[\\p{L}_][\\p{L}\\p{N}_]*"))problem(tag,name,holder,"无效局部变量名称: "+value);}
    private static void problem(XmlTag tag,String attr,ProblemsHolder holder,String message){XmlAttribute attribute=tag.getAttribute(attr);if(attribute!=null && attribute.getValueElement()!=null)holder.registerProblem(attribute.getValueElement(),message);}
}
