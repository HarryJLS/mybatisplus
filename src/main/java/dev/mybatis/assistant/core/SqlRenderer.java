package dev.mybatis.assistant.core;

import java.io.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLIncludeTransformer;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.*;
import org.apache.ibatis.session.Configuration;
import com.google.gson.*;

/** Renders without connecting to a database or instantiating project result classes. */
public final class SqlRenderer {
    private SqlRenderer() {}
    public record Result(String sql,List<Object> parameters,List<String> notes) {
        public Result(String sql,List<Object> parameters){this(sql,parameters,List.of());}
        public String display() {return "SQL（预编译）:\n"+sql+"\n\n参数（按顺序）:\n"+new GsonBuilder().setPrettyPrinting().create().toJson(parameters)+(notes.isEmpty()?"":"\n\n求值范围：\n"+String.join("\n",notes));}
    }
    public static Document parseXml(String xml) throws Exception {
        var f=DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
    }
    public static Result render(String xml,String id,String json,List<String> relatedMappers) throws Exception {
        return render(xml,id,json,relatedMappers,()->{});
    }
    public static Result render(String xml,String id,String json,List<String> relatedMappers,Runnable cancellation) throws Exception {
        cancellation.run();
        if(xml.length()>4*1024*1024 || json.length()>1024*1024)throw new IllegalArgumentException("SQL 预览输入过大");
        if(relatedMappers.size()>64 || relatedMappers.stream().mapToLong(String::length).sum()+xml.length()>8*1024*1024)throw new IllegalArgumentException("关联 Mapper 总输入超过 8 MiB");
        PreviewSafety.jsonText(json);
        var doc=parseXml(xml);
        if("sqlMap".equals(doc.getDocumentElement().getTagName()))return LegacySqlRenderer.render(doc,id,jsonValue(json),relatedMappers);
        if(!"mapper".equals(doc.getDocumentElement().getTagName()))throw new IllegalArgumentException("SQL 预览需要 mapper 或 sqlMap 根元素");
        Configuration config=new Configuration();XPathParser parser=new XPathParser(doc,false);
        String namespace=parser.evalString("/mapper/@namespace");
        for(String mapper:relatedMappers) registerFragments(config,new XPathParser(parseXml(mapper),false));
        registerFragments(config,parser);
        XNode statement=null;
        for(XNode node:parser.evalNodes("/mapper/*")) if(Set.of("select","insert","update","delete").contains(node.getName()) && id.equals(node.getStringAttribute("id"))) {statement=node;break;}
        if(statement==null) throw new IllegalArgumentException("找不到语句: "+id);
        checkIncludes(statement.getNode(),config,namespace,new HashSet<>(),0,new long[]{0,0});
        MapperBuilderAssistant assistant=new MapperBuilderAssistant(config,"preview");assistant.setCurrentNamespace(namespace);
        new XMLIncludeTransformer(config,assistant).applyIncludes(statement.getNode());
        JsonElement element=JsonParser.parseString(json);
        if(!element.isJsonObject()) throw new IllegalArgumentException("参数必须是 JSON 对象");
        PreviewExpressions expressions=new PreviewExpressions(cancellation);
        PreviewSafety.script(statement.getNode(),element,expressions);
        SqlSource source=new PreviewScriptBuilder(config,expressions).build(statement);
        Map<String,Object> args=objectMap(element.getAsJsonObject());
        BoundSql bound=source.getBoundSql(args);List<Object> values=new ArrayList<>();List<String> notes=new ArrayList<>();long valueSize=0;
        if(bound.getSql().length()>PreviewSafety.MAX_OUTPUT || bound.getParameterMappings().size()>PreviewSafety.MAX_PARAMS)throw new IllegalArgumentException("预览输出或参数超过上限");
        for(ParameterMapping p:bound.getParameterMappings()) {
            if(p.getMode()==ParameterMode.OUT){notes.add("OUT 参数只预览占位，不执行: "+p.getProperty());continue;}
            if(p.getMode()==ParameterMode.INOUT)notes.add("INOUT 参数仅显示输入值，不注册输出，不执行: "+p.getProperty());
            Object value=bound.hasAdditionalParameter(p.getProperty())?bound.getAdditionalParameter(p.getProperty()):config.newMetaObject(args).getValue(p.getProperty());
            valueSize+=PreviewSafety.parameterSize(value);if(valueSize>PreviewSafety.MAX_OUTPUT)throw new IllegalArgumentException("参数展示超过 4 MiB");values.add(value);
        }
        return new Result(bound.getSql().trim(),Collections.unmodifiableList(values),List.copyOf(notes));
    }
    public static Object jsonValue(String json){return value(JsonParser.parseString(json));}
    private static void checkIncludes(org.w3c.dom.Node node,Configuration config,String namespace,Set<String> stack,int depth,long[] budget){
        if(depth>64)throw new IllegalArgumentException("SQL 片段引用深度超过 64");
        if(++budget[0]>100000)throw new IllegalArgumentException("include 展开节点超过 100000");
        if(node.getNodeType()==org.w3c.dom.Node.TEXT_NODE || node.getNodeType()==org.w3c.dom.Node.CDATA_SECTION_NODE){budget[1]+=node.getNodeValue().length();if(budget[1]>4*1024*1024)throw new IllegalArgumentException("include 文本超过 4 MiB");}
        if(node instanceof org.w3c.dom.Element element && element.getTagName().equals("include")){
            String id=element.getAttribute("refid");if(id.contains("${"))throw new IllegalArgumentException("动态 include refid 未支持离线预览");
            String qualified=id.contains(".")?id:namespace+"."+id;
            if(!stack.add(qualified))throw new IllegalArgumentException("SQL 片段存在循环引用: "+qualified);
            XNode fragment=config.getSqlFragments().get(qualified);
            if(fragment!=null)checkIncludes(fragment.getNode(),config,namespace,stack,depth+1,budget);
            stack.remove(qualified);return;
        }
        for(var child=node.getFirstChild();child!=null;child=child.getNextSibling())checkIncludes(child,config,namespace,stack,depth+1,budget);
    }
    private static void registerFragments(Configuration c,XPathParser parser) {
        String ns=parser.evalString("/mapper/@namespace");
        for(XNode sql:parser.evalNodes("/mapper/sql")) c.getSqlFragments().put(ns+"."+sql.getStringAttribute("id"),sql);
    }
    private static Map<String,Object> objectMap(JsonObject object) {
        Map<String,Object> map=new LinkedHashMap<>();object.entrySet().forEach(e->map.put(e.getKey(),value(e.getValue())));return map;
    }
    private static Object value(JsonElement e) {
        if(e.isJsonNull())return null;
        if(e.isJsonObject())return objectMap(e.getAsJsonObject());
        if(e.isJsonArray()){List<Object> l=new ArrayList<>();e.getAsJsonArray().forEach(v->l.add(value(v)));return l;}
        var p=e.getAsJsonPrimitive();if(p.isBoolean())return p.getAsBoolean();if(p.isNumber())return p.getAsBigDecimal();return p.getAsString();
    }
}
