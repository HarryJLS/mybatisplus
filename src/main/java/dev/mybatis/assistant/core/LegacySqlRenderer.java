package dev.mybatis.assistant.core;

import java.math.BigDecimal;
import java.util.*;
import org.w3c.dom.*;

/** Request-scoped iBATIS 2 evaluator for JSON input, without reflection or a database runtime. */
public final class LegacySqlRenderer {
    private LegacySqlRenderer(){}
    private static final Set<String> STATEMENTS=Set.of("select","insert","update","delete","procedure","statement");
    private static final Set<String> CONDITIONAL=Set.of("isEmpty","isNotEmpty","isNull","isNotNull","isEqual","isNotEqual","isGreaterThan","isGreaterEqual","isLessThan","isLessEqual","isPropertyAvailable","isNotPropertyAvailable","isParameterPresent","isNotParameterPresent");
    private static final Set<String> PARAM_FIELDS=Set.of("javaType","jdbcType","mode","nullValue","handler","numericScale");
    private static final int MAX_OUTPUT=4*1024*1024,MAX_PARAMETERS=10_000,MAX_STEPS=100_000;
    private record Definition(Element element,String namespace){}
    private record Frame(String property,int index){}
    private record Lookup(boolean present,Object value){}
    private static final class Marker {
        final String remove;boolean active;
        Marker(String remove,boolean active){this.remove=remove;this.active=active;}
    }
    private static final class State {
        final Object input;final String namespace;
        final Map<String,List<Definition>> fragments=new HashMap<>(),parameterMaps=new HashMap<>();
        final List<Object> parameters=new ArrayList<>();final LinkedHashSet<String> notes=new LinkedHashSet<>();
        final List<Marker> markers=new ArrayList<>();final List<Frame> frames=new ArrayList<>();
        final Set<Element> expanding=Collections.newSetFromMap(new IdentityHashMap<>());
        int steps;boolean dynamic;
        State(Object input,String namespace){this.input=input;this.namespace=namespace;}
        void step(){if(++steps>MAX_STEPS)throw problem("展开步骤超过 "+MAX_STEPS+"，请缩小参数集合");}
        void parameter(Object value){if(parameters.size()>=MAX_PARAMETERS)throw problem("参数超过 "+MAX_PARAMETERS);parameters.add(value);}
        String path(String raw){
            String path=raw;
            for(Frame frame:frames){String token=frame.property()+"[]";int i=path.indexOf(token);if(i>=0)path=path.substring(0,i)+frame.property()+"["+frame.index()+"]"+path.substring(i+token.length());}
            return path;
        }
    }
    private static IllegalArgumentException problem(String message){return new IllegalArgumentException("iBATIS SQL 预览："+message);}
    public static SqlRenderer.Result render(Document document,String id,Object input,List<String> related)throws Exception{
        Element root=document.getDocumentElement();String ns=root.getAttribute("namespace");State state=new State(input,ns);
        register(state,root);
        if(related.size()>64)throw problem("关联 sqlMap 超过 64 个");
        for(String xml:related){if(xml.length()>MAX_OUTPUT)throw problem("关联 XML 超过 4 MiB");Document other=SqlRenderer.parseXml(xml);if(!other.getDocumentElement().getTagName().equals("sqlMap"))throw problem("include 不能混用 MyBatis mapper 与 iBATIS sqlMap");register(state,other.getDocumentElement());}
        Element statement=null;
        for(Node child=root.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element e && STATEMENTS.contains(e.getTagName())){
            String raw=e.getAttribute("id");if(id.equals(raw) || id.equals(local(ns,raw)) || id.equals(qualified(ns,raw))){if(statement!=null)throw problem("语句 id 不唯一："+id);statement=e;}
        }
        if(statement==null)throw problem("找不到语句："+id);
        validate(statement,state,0);
        String sql=children(statement,state,0).trim();
        String parameterMap=attr(statement,"parameterMap");
        if(parameterMap!=null){
            if(state.dynamic)throw problem("旧动态标签与外置 parameterMap 的组合不支持离线绑定；请使用内联 #property# 参数");
            if(!state.parameters.isEmpty())throw problem("不能混用外置 parameterMap 与内联参数");
            Definition map=definition(state.parameterMaps,parameterMap,state.namespace);
            for(Node n=map.element().getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element p && p.getTagName().equals("parameter")){
                if(attr(p,"typeHandler")!=null || attr(p,"handler")!=null)throw problem("自定义 TypeHandler 需要项目运行时，不能离线执行");
                bind(state,required(p,"property"),attr(p,"nullValue"),Objects.requireNonNullElse(attr(p,"mode"),"IN"));
            }
            if(jdbcMarkers(sql)!=state.parameters.size())throw problem("parameterMap 参数数与 SQL 的 ? 数量不一致");
        }
        return new SqlRenderer.Result(sql,Collections.unmodifiableList(state.parameters),List.copyOf(state.notes));
    }
    private static String local(String ns,String id){return !ns.isEmpty() && id.startsWith(ns+".")?id.substring(ns.length()+1):id;}
    private static String qualified(String ns,String id){return ns.isEmpty() || id.startsWith(ns+".")?id:ns+"."+id;}
    private static void register(State state,Element root){
        String ns=root.getAttribute("namespace");
        for(Node n=root.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e && Set.of("sql","parameterMap").contains(e.getTagName())){
            String id=required(e,"id");Map<String,List<Definition>> index=e.getTagName().equals("sql")?state.fragments:state.parameterMaps;
            index.computeIfAbsent(qualified(ns,id),key->new ArrayList<>()).add(new Definition(e,ns));
        }
    }
    private static Definition definition(Map<String,List<Definition>> index,String id,String ns){
        List<Definition> matches=index.get(id);if(matches==null)matches=index.get(qualified(ns,id));
        if(matches==null || matches.isEmpty())throw problem("找不到定义："+id);
        if(matches.size()!=1)throw problem("定义存在歧义："+id);return matches.getFirst();
    }
    private static String attr(Element e,String name){return e.hasAttribute(name)?e.getAttribute(name):null;}
    private static String required(Element e,String name){String value=attr(e,name);if(value==null || value.isBlank())throw problem(e.getTagName()+" 缺少 "+name);return value;}
    private static void validate(Node node,State state,int depth){
        state.step();if(depth>64)throw problem("XML/include 嵌套深度超过 64");
        if(node instanceof Element e){
            String tag=e.getTagName();
            if(tag.equals("selectKey"))return;
            if(tag.equals("include")){
                Definition fragment=definition(state.fragments,required(e,"refid"),state.namespace);
                if(!state.expanding.add(fragment.element()))throw problem("SQL 片段循环引用："+e.getAttribute("refid"));
                validate(fragment.element(),state,depth+1);state.expanding.remove(fragment.element());return;
            }
            if(!STATEMENTS.contains(tag) && !tag.equals("sql") && !tag.equals("dynamic") && !tag.equals("iterate") && !CONDITIONAL.contains(tag))throw problem("不支持标签："+tag);
            if(tag.equals("dynamic") || tag.equals("iterate") || CONDITIONAL.contains(tag))state.dynamic=true;
            if(Set.of("isEqual","isNotEqual","isGreaterThan","isGreaterEqual","isLessThan","isLessEqual").contains(tag) && attr(e,"compareValue")==null && attr(e,"compareProperty")==null)throw problem(tag+" 缺少 compareValue / compareProperty");
            if(tag.equals("isPropertyAvailable") || tag.equals("isNotPropertyAvailable"))required(e,"property");
            String removal=attr(e,"removeFirstPrepend");if(removal!=null && !Set.of("true","false","iterate").contains(removal))throw problem("removeFirstPrepend 只能为 true、false 或 iterate");
        }
        for(Node n=node.getFirstChild();n!=null;n=n.getNextSibling())validate(n,state,depth+1);
    }
    private static String children(Node parent,State state,int depth){
        if(depth>64)throw problem("展开深度超过 64");StringBuilder sql=new StringBuilder();
        for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling()){
            state.step();
            if(child.getNodeType()==Node.TEXT_NODE || child.getNodeType()==Node.CDATA_SECTION_NODE)append(sql,text(child.getNodeValue(),state));
            else if(child instanceof Element e){
                if(e.getTagName().equals("include"))append(sql,children(definition(state.fragments,required(e,"refid"),state.namespace).element(),state,depth+1));
                else if(e.getTagName().equals("selectKey"))state.notes.add("selectKey 是独立查询，本次只渲染主语句，未执行或求值 selectKey。");
                else append(sql,element(e,state,depth+1));
            }
        }
        return sql.toString();
    }
    private static Marker push(Element element,State state){
        String remove=attr(element,"removeFirstPrepend"),prepend=attr(element,"prepend");boolean active;
        if(element.getTagName().equals("dynamic"))active=prepend!=null && !prepend.isEmpty();
        else if("true".equals(remove) || "iterate".equals(remove))active=true;
        else active=(prepend==null || prepend.isEmpty()) && !state.markers.isEmpty() && Set.of("true","iterate").contains(Objects.requireNonNullElse(state.markers.getLast().remove,""));
        Marker marker=new Marker(remove,active);state.markers.add(marker);return marker;
    }
    private static String element(Element e,State state,int depth){
        Marker marker=push(e,state);
        try{
            String body;
            if(e.getTagName().equals("iterate"))body=iterate(e,state,marker,depth);
            else{
                if(CONDITIONAL.contains(e.getTagName()) && !condition(e,state))return "";
                body=children(e,state,depth);
                if(!body.isBlank())body=Objects.requireNonNullElse(attr(e,"open"),"")+body+Objects.requireNonNullElse(attr(e,"close"),"");
            }
            String prepend=attr(e,"prepend");
            if(!body.isBlank() && prepend!=null && !prepend.isEmpty()){
                Marker parent=state.markers.size()>1?state.markers.get(state.markers.size()-2):null;
                if(parent!=null && parent.active)parent.active=false;else body=prepend+body;
            }
            if(body.length()>MAX_OUTPUT)throw problem("SQL 输出超过 4 MiB");return body;
        }finally{state.markers.removeLast();}
    }
    private static String iterate(Element e,State state,Marker marker,int depth){
        String property=Objects.requireNonNullElse(attr(e,"property"),"");String resolved=state.path(property);Object collection=lookup(state.input,resolved).value();
        if(!(collection instanceof List<?> list))throw problem("iterate 的参数必须为非 null JSON 数组："+property);
        if(list.size()>10_000)throw problem("单次 iterate 超过 10000 个元素");
        StringBuilder body=new StringBuilder();boolean content=false;
        for(int index=0;index<list.size();index++){
            state.step();if(index>0 && "iterate".equals(marker.remove))marker.active=true;
            state.frames.add(new Frame(resolved,index));String part;try{part=children(e,state,depth);}finally{state.frames.removeLast();}
            if(!part.isBlank()){append(body,content?Objects.requireNonNullElse(attr(e,"conjunction"),""):Objects.requireNonNullElse(attr(e,"open"),""));append(body,part);content=true;}
        }
        if(content)append(body,Objects.requireNonNullElse(attr(e,"close"),""));return body.toString();
    }
    private static boolean condition(Element e,State state){
        String tag=e.getTagName(),property=attr(e,"property");
        if(tag.equals("isParameterPresent"))return state.input!=null;
        if(tag.equals("isNotParameterPresent"))return state.input==null;
        if(tag.equals("isPropertyAvailable") || tag.equals("isNotPropertyAvailable")){
            // JSON objects are Maps: iBATIS checks the direct key here, even for a dotted key.
            boolean available=state.input instanceof Map<?,?> map && map.containsKey(property);
            return tag.equals("isPropertyAvailable")?available:!available;
        }
        Object left=lookup(state.input,property==null?"":state.path(property)).value();
        if(tag.equals("isNull"))return left==null;if(tag.equals("isNotNull"))return left!=null;
        if(tag.equals("isEmpty"))return empty(left);if(tag.equals("isNotEmpty"))return !empty(left);
        Object right=attr(e,"compareProperty")!=null?lookup(state.input,e.getAttribute("compareProperty")).value():e.getAttribute("compareValue");
        Integer order=compare(left,right);
        return switch(tag){
            case "isEqual"->order!=null && order==0;case "isNotEqual"->order==null || order!=0;
            case "isGreaterThan"->order!=null && order>0;case "isGreaterEqual"->order!=null && order>=0;
            case "isLessThan"->order!=null && order<0;case "isLessEqual"->order!=null && order<=0;
            default->throw problem("不支持条件："+tag);
        };
    }
    private static boolean empty(Object value){return value==null || value instanceof List<?> list && list.isEmpty() || value instanceof String text && text.isEmpty();}
    private static Integer compare(Object left,Object right){
        if(left==null || right==null)return left==right?0:null;
        try{
            if(left instanceof Number n)return new BigDecimal(n.toString()).compareTo(new BigDecimal(right.toString()));
            if(left instanceof Boolean b)return Boolean.compare(b,Boolean.parseBoolean(right.toString()));
            if(left instanceof Map<?,?> || left instanceof List<?>)throw problem("集合/对象不能用作排序比较值");
            return left.toString().compareTo(right.toString());
        }catch(NumberFormatException e){throw problem("比较值无法转换为数字："+right);}
    }
    private static String text(String input,State state){
        StringBuilder sql=new StringBuilder();
        for(int i=0;i<input.length();){
            char marker=input.charAt(i);
            if(marker!='#' && marker!='$'){int end=i+1;while(end<input.length() && input.charAt(end)!='#' && input.charAt(end)!='$')end++;append(sql,input.substring(i,end));i=end;continue;}
            if(i+1<input.length() && input.charAt(i+1)==marker){append(sql,String.valueOf(marker));i+=2;continue;}
            int end=input.indexOf(marker,i+1);if(end<0)throw problem("参数分隔符未闭合："+marker);
            String token=input.substring(i+1,end);if(token.isEmpty())throw problem("参数名为空");
            if(marker=='#'){
                String property=token,nullValue=null,mode="IN";
                if(token.contains(":")){String[] parts=token.split(":",3);property=parts[0];if(parts.length<2 || parts[1].isBlank())throw problem("JDBC 类型缺失："+token);if(parts.length>2)nullValue=parts[2];}
                else if(token.contains(",")){
                    String[] fields=token.split(",");property=fields[0];
                    for(int f=1;f<fields.length;f++){String[] option=fields[f].split("=",2);if(option.length!=2 || !PARAM_FIELDS.contains(option[0]))throw problem("内联参数选项不正确："+fields[f]);String name=option[0],value=option[1];if(name.equals("handler"))throw problem("自定义 TypeHandler 需要项目运行时，不能离线执行");if(name.equals("nullValue"))nullValue=value;if(name.equals("mode"))mode=value;if(name.equals("numericScale")){try{if(Integer.parseInt(value)<0)throw problem("numericScale 必须非负");}catch(NumberFormatException ex){throw problem("numericScale 必须为整数");}}if(name.equals("javaType"))state.notes.add("javaType 仅作为声明元数据；预览显示 JSON 值，不构造业务对象或执行类型转换器。");}
                }
                bind(state,property,nullValue,mode);append(sql,"?");
            }else{
                Object value=valueForBinding(state,token);if(value instanceof Map<?,?> || value instanceof List<?>)throw problem("$…$ 拼接只支持标量 JSON 值");append(sql,value==null?"":value.toString());state.notes.add("$…$ 按输入直接拼接；本次未验证数据库语法或执行 SQL。");
            }
            i=end+1;
        }
        return sql.toString();
    }
    private static void bind(State state,String property,String nullValue,String mode){
        if(!Set.of("IN","OUT","INOUT").contains(mode))throw problem("未知参数模式："+mode);
        Object value=mode.equals("OUT")?null:valueForBinding(state,property);
        if(value!=null && nullValue!=null){Integer cmp=compare(value,nullValue);if(cmp!=null && cmp==0)value=null;}
        state.parameter(value);
        if(!mode.equals("IN"))state.notes.add("第 "+state.parameters.size()+" 个占位符为 "+mode+" 参数 "+property+"；输出值需数据库运行时返回。");
    }
    private static Object valueForBinding(State state,String property){
        if(state.input==null || state.input instanceof String || state.input instanceof Number || state.input instanceof Boolean)return state.input;
        return lookup(state.input,state.path(property)).value();
    }
    private static Lookup lookup(Object root,String path){
        if(path==null || path.isEmpty())return new Lookup(root!=null,root);
        Object current=root;boolean present=true;int i=0;
        while(i<path.length()){
            if(path.charAt(i)=='.'){i++;if(i==path.length())throw problem("属性路径不完整："+path);continue;}
            if(path.charAt(i)=='['){
                int end=path.indexOf(']',i);if(end<0)throw problem("索引未闭合："+path);String index=path.substring(i+1,end);
                if(index.isEmpty())throw problem("[] 未处于对应的 iterate 作用域："+path);
                int position;try{position=Integer.parseInt(index);}catch(NumberFormatException e){throw problem("数组索引必须为整数："+path);}
                if(current==null)return new Lookup(false,null);
                if(!(current instanceof List<?> list) || position<0 || position>=list.size())throw problem("数组索引越界或类型错误："+path);
                current=list.get(position);i=end+1;
            }else{
                int end=i;while(end<path.length() && path.charAt(end)!='.' && path.charAt(end)!='[')end++;
                String key=path.substring(i,end);if(current==null)return new Lookup(false,null);
                if(!(current instanceof Map<?,?> map))throw problem("属性不是 JSON 对象字段："+path);
                present=map.containsKey(key);current=map.get(key);i=end;
            }
        }
        return new Lookup(present,current);
    }
    private static void append(StringBuilder target,String text){if(target.length()+text.length()>MAX_OUTPUT)throw problem("SQL 输出超过 4 MiB");target.append(text);}
    private static int jdbcMarkers(String sql){
        int count=0;char quote=0;
        for(int i=0;i<sql.length();i++){
            char c=sql.charAt(i);
            if(quote!=0){if(c=='\\'){i++;continue;}if(c==quote){if(i+1<sql.length() && sql.charAt(i+1)==quote)i++;else quote=0;}continue;}
            if(c=='\'' || c=='"' || c=='`' || c=='['){quote=c=='['?']':c;continue;}
            if(c=='-' && i+1<sql.length() && sql.charAt(i+1)=='-'){while(i<sql.length() && sql.charAt(i)!='\n')i++;continue;}
            if(c=='/' && i+1<sql.length() && sql.charAt(i+1)=='*'){int end=sql.indexOf("*/",i+2);i=end<0?sql.length():end+1;continue;}
            if(c=='?')count++;
        }
        return count;
    }
}
