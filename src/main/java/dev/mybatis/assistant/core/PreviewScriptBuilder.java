package dev.mybatis.assistant.core;

import java.util.*;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.*;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.scripting.xmltags.*;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.SimpleTypeRegistry;
import org.w3c.dom.Node;

/** Reuses MyBatis SQL nodes/parameter handling with request-scoped OGNL evaluation. */
final class PreviewScriptBuilder {
    private final Configuration configuration;
    private final PreviewExpressions expressions;
    private boolean dynamic;
    PreviewScriptBuilder(Configuration configuration,PreviewExpressions expressions){this.configuration=configuration;this.expressions=expressions;}
    SqlSource build(XNode statement){
        MixedSqlNode root=children(statement);
        return dynamic?new DynamicSqlSource(configuration,root):new RawSqlSource(configuration,root,Map.class);
    }
    private MixedSqlNode children(XNode parent){
        List<SqlNode> children=new ArrayList<>();
        for(Node node=parent.getNode().getFirstChild();node!=null;node=node.getNextSibling()){
            expressions.checkCanceled();XNode child=parent.newXNode(node);
            if(node.getNodeType()==Node.TEXT_NODE || node.getNodeType()==Node.CDATA_SECTION_NODE){
                String text=child.getStringBody("");
                if(new TextSqlNode(text).isDynamic()){
                    dynamic=true;children.add(context->{
                        String sql=new GenericTokenParser("${","}",expression->{
                            Object parameter=context.getBindings().get("_parameter");
                            if(parameter==null)context.bind("value",null);
                            else if(SimpleTypeRegistry.isSimpleType(parameter.getClass()))context.bind("value",parameter);
                            Object value=expressions.value(expression,context.getBindings());return value==null?"":String.valueOf(value);
                        }).parse(text);
                        context.appendSql(sql);return true;
                    });
                }else children.add(new StaticTextSqlNode(text));
            }else if(node.getNodeType()==Node.ELEMENT_NODE){dynamic=true;children.add(element(child));}
        }
        return new MixedSqlNode(children);
    }
    private SqlNode element(XNode node){
        return switch(node.getName()){
            case "if","when" -> {
                SqlNode contents=children(node);String test=node.getStringAttribute("test");
                yield context->{if(expressions.bool(test,context.getBindings())){contents.apply(context);return true;}return false;};
            }
            case "bind" -> {
                String name=node.getStringAttribute("name"),value=node.getStringAttribute("value");
                yield context->{context.bind(name,expressions.value(value,context.getBindings()));return true;};
            }
            case "foreach" -> new PreviewForEachSqlNode(configuration,expressions,children(node),node.getStringAttribute("collection"),node.getBooleanAttribute("nullable"),node.getStringAttribute("index"),node.getStringAttribute("item"),node.getStringAttribute("open"),node.getStringAttribute("close"),node.getStringAttribute("separator"));
            case "where" -> new WhereSqlNode(configuration,children(node));
            case "set" -> new SetSqlNode(configuration,children(node));
            case "trim" -> new TrimSqlNode(configuration,children(node),node.getStringAttribute("prefix"),node.getStringAttribute("prefixOverrides"),node.getStringAttribute("suffix"),node.getStringAttribute("suffixOverrides"));
            case "otherwise" -> children(node);
            case "choose" -> {
                List<SqlNode> conditions=new ArrayList<>();SqlNode fallback=null;
                for(XNode child:node.getChildren()){
                    if(child.getName().equals("when") || child.getName().equals("if"))conditions.add(element(child));
                    else if(child.getName().equals("otherwise")){
                        if(fallback!=null)throw new BuilderException("Too many default (otherwise) elements in choose statement.");
                        fallback=children(child);
                    }else throw new BuilderException("Unknown element <"+child.getName()+"> in choose statement.");
                }yield new ChooseSqlNode(conditions,fallback);
            }
            default -> throw new BuilderException("Unknown element <"+node.getName()+"> in SQL statement.");
        };
    }
}
