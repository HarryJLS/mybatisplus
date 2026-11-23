package dev.mybatis.assistant.ide;

import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.xml.XmlTag;
import dev.mybatis.assistant.core.Schema;
import java.util.*;

/** Deterministic column/property matches; ambiguous matches remain visible for review. */
public final class ResultMapEdits {
    private ResultMapEdits() {}
    public record Plan(String xml,List<String> unmatched,String id,long psiStamp) {}
    private record Group(String property,String type,boolean collection) {}
    private record Leaf(String path,String column,List<Group> groups) {}
    private static final class Node {
        Group group;final Map<String,Node> children=new LinkedHashMap<>();final Map<String,String> columns=new LinkedHashMap<>();
        List<String> allColumns(){List<String> result=new ArrayList<>(columns.values());children.values().forEach(n->result.addAll(n.allColumns()));return result;}
    }
    public static Plan generate(XmlTag statement){
        if(!statement.getName().equals("select") || MapperModel.isLegacy(MapperModel.root(statement)))throw new IllegalArgumentException("请选择 MyBatis SELECT 语句");
        String typeName=statement.getAttributeValue("resultType");
        XmlTag existing=MapperModel.definition(statement,statement.getAttributeValue("resultMap"),"resultMap");
        PsiType type=existing!=null?MappingTypes.named(existing,existing.getAttributeValue("type")):typeName==null?null:MappingTypes.named(statement,typeName);
        if(type==null){PsiMethod method=MapperModel.method(statement);if(method!=null){PsiClass declaring=method.getContainingClass(),mapper=MapperModel.mapperClass(MapperModel.root(statement));PsiSubstitutor sub=declaring==null || mapper==null?PsiSubstitutor.EMPTY:com.intellij.psi.util.TypeConversionUtil.getSuperClassSubstitutor(declaring,mapper,PsiSubstitutor.EMPTY);type=MapperModel.entityType(sub.substitute(method.getReturnType()));}}
        if(!(type instanceof PsiClassType cls) || ParameterModel.isMap(type) || ParameterModel.simple(type) || cls.resolve()==null || cls.resolve() instanceof PsiTypeParameter)throw new IllegalArgumentException("请先指定可解析的实体 resultType；Map/标量/未确定泛型结果没有可生成的实体属性");
        var projection=StatementSql.projection(statement);if(!projection.complete())throw new IllegalArgumentException("SELECT 投影不完整，不能自动生成 resultMap："+projection.reason()+"；请为表达式命名并展开 * / 动态列");
        List<Leaf> leaves=new ArrayList<>();enumerate(type,"",List.of(),leaves,new HashSet<>(),0);
        Node root=new Node();List<String> unmatched=new ArrayList<>();Set<String> used=new HashSet<>(),seenColumns=new HashSet<>();
        for(String column:projection.columns()){
            if(!seenColumns.add(column.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("SELECT 输出列重复，先设置唯一别名: "+column);
            List<Leaf> matches=leaves.stream().filter(leaf->normal(column).equals(normal(leaf.path())) || normal(column).equals(normal(leaf.column())) && leaf.groups().isEmpty()).toList();
            if(matches.size()!=1 || !used.add(matches.getFirst().path())){unmatched.add(column);continue;}
            Leaf leaf=matches.getFirst();Node node=root;for(Group group:leaf.groups()){Node child=node.children.computeIfAbsent(group.property(),key->new Node());child.group=group;node=child;}
            String property=leaf.path().substring(leaf.path().lastIndexOf('.')+1);node.columns.put(property,column);
        }
        String base=Objects.requireNonNullElse(statement.getAttributeValue("id"),"Select")+"ResultMap",id=base;int suffix=2;
        while(MapperModel.definition(statement,id,"resultMap")!=null){if(suffix>1000)throw new IllegalArgumentException("resultMap id 冲突过多");id=base+suffix++;}
        StringBuilder xml=new StringBuilder("<resultMap id=\""+Schema.xml(id)+"\" type=\""+Schema.xml(type.getCanonicalText())+"\">\n");
        write(root,xml,"  ","");for(String column:unmatched)xml.append("  <!-- 未匹配输出列: ").append(Schema.xml(column).replace("--","- -")).append("；请补充 column/property 映射 -->\n");xml.append("</resultMap>");
        return new Plan(xml.toString(),List.copyOf(unmatched),id,com.intellij.psi.util.PsiModificationTracker.getInstance(statement.getProject()).getModificationCount());
    }
    private static void enumerate(PsiType type,String path,List<Group> groups,List<Leaf> leaves,Set<String> stack,int depth){
        if(depth>6 || leaves.size()>512 || type==null || !stack.add(type.getCanonicalText()))return;
        try{for(var entry:ParameterModel.properties(type).entrySet()){
            if(leaves.size()>512)throw new IllegalArgumentException("实体属性超过 512 个");String property=entry.getKey();PsiType member=entry.getValue().type();String next=path.isEmpty()?property:path+"."+property;
            if(ParameterModel.simple(member) || member instanceof PsiPrimitiveType || member instanceof PsiClassType ct && ct.resolve()!=null && ct.resolve().isEnum()){
                String column=property;PsiElement declaration=entry.getValue().declaration();if(declaration instanceof PsiModifierListOwner owner){PsiAnnotation annotation=owner.getAnnotation("com.baomidou.mybatisplus.annotation.TableField");if(annotation==null)annotation=owner.getAnnotation("com.baomidou.mybatisplus.annotation.TableId");if(annotation!=null && annotation.findAttributeValue("value") instanceof PsiLiteralExpression literal && literal.getValue() instanceof String value && !value.isBlank())column=value;}
                leaves.add(new Leaf(next,column,List.copyOf(groups)));
            }else if(member!=null && !ParameterModel.isMap(member)){
                boolean collection=member instanceof PsiArrayType || InheritanceUtil.isInheritor(member,"java.lang.Iterable");PsiType nested=collection?ParameterModel.elementType(member):member;
                if(nested instanceof PsiClassType && !nested.getCanonicalText().startsWith("java.")){List<Group> extended=new ArrayList<>(groups);extended.add(new Group(property,nested.getCanonicalText(),collection));enumerate(nested,next,extended,leaves,stack,depth+1);}
            }
        }}finally{stack.remove(type.getCanonicalText());}
    }
    private static String normal(String name){return name.replace("_","").replace(".","").toLowerCase(Locale.ROOT);}
    private static void write(Node node,StringBuilder xml,String indent,String inherited){
        for(var entry:node.columns.entrySet())xml.append(indent).append(entry.getKey().equalsIgnoreCase("id")?"<id":"<result").append(" column=\"").append(Schema.xml(entry.getValue().startsWith(inherited)?entry.getValue().substring(inherited.length()):entry.getValue())).append("\" property=\"").append(Schema.xml(entry.getKey())).append("\"/>\n");
        for(Node child:node.children.values()){
            Group group=child.group;String prefix=group.property()+"_",expected=inherited+prefix;
            boolean prefixed=!child.allColumns().isEmpty() && child.allColumns().stream().allMatch(c->c.startsWith(expected));String tag=group.collection()?"collection":"association";
            xml.append(indent).append('<').append(tag).append(" property=\"").append(Schema.xml(group.property())).append("\" ").append(group.collection()?"ofType":"javaType").append("=\"").append(Schema.xml(group.type())).append('"');
            if(prefixed)xml.append(" columnPrefix=\"").append(Schema.xml(prefix)).append('"');xml.append(">\n");write(child,xml,indent+"  ",prefixed?expected:inherited);xml.append(indent).append("</").append(tag).append(">\n");
        }
    }
}
