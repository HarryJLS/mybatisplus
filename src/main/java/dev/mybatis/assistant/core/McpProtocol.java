package dev.mybatis.assistant.core;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;

/** Stateless JSON-RPC MCP core. HTTP, project access and authentication belong to the IDE layer. */
public final class McpProtocol {
    private McpProtocol() {}
    public static final Set<String> VERSIONS=Set.of("2025-03-26","2025-06-18");
    public interface Backend { JsonObject call(String name,JsonObject arguments)throws Exception; }
    public record Reply(int status,JsonObject body) {}
    public static Reply handle(String body,Backend backend){
        JsonElement id=JsonNull.INSTANCE;
        try{
            if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576 || !bounded(body))return new Reply(413,error(id,-32600,"请求超出上限"));
            JsonReader reader=new JsonReader(new StringReader(body));reader.setStrictness(Strictness.STRICT);
            JsonElement parsed=JsonParser.parseReader(reader);if(reader.peek()!=JsonToken.END_DOCUMENT)throw new JsonSyntaxException("trailing JSON");
            if(!parsed.isJsonObject())return new Reply(400,error(id,-32600,"仅支持单个 JSON-RPC 消息"));
            JsonObject request=parsed.getAsJsonObject();
            if(!request.has("jsonrpc") || !request.get("jsonrpc").isJsonPrimitive() || !request.get("jsonrpc").getAsString().equals("2.0"))return new Reply(400,error(id,-32600,"jsonrpc 必须为 2.0"));
            if(request.has("id")){id=request.get("id");if(!id.isJsonNull() && (!id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean()))return new Reply(400,error(JsonNull.INSTANCE,-32600,"无效请求 id"));}
            if(!request.has("method")){
                if(request.has("id") && (request.has("result") || request.has("error")))return new Reply(202,null);
                return new Reply(400,error(id,-32600,"缺少 method"));
            }
            if(!request.get("method").isJsonPrimitive() || !request.getAsJsonPrimitive("method").isString())return new Reply(400,error(id,-32600,"无效 method"));
            String method=request.get("method").getAsString();
            if(!request.has("id"))return method.startsWith("notifications/")?new Reply(202,null):new Reply(400,error(id,-32600,"请求需要 id"));
            if(request.has("params") && !request.get("params").isJsonObject())return new Reply(200,error(id,-32602,"params 必须为对象"));
            JsonObject params=request.has("params")?request.getAsJsonObject("params"):new JsonObject(),result=new JsonObject();
            switch(method){
                case "initialize" -> {
                    String version=string(params,"protocolVersion",true);
                    result.addProperty("protocolVersion",VERSIONS.contains(version)?version:"2025-06-18");
                    result.add("capabilities",JsonParser.parseString("{\"tools\":{\"listChanged\":false}}"));
                    result.add("serverInfo",JsonParser.parseString("{\"name\":\"mybatis-assistant\",\"version\":\"0.5.1\"}"));
                }
                case "ping" -> {}
                case "tools/list" -> {if(params.has("cursor"))throw new IllegalArgumentException("此工具列表不需要 cursor");result.add("tools",tools());}
                case "tools/call" -> {
                    String name=string(params,"name",true);
                    if(params.has("arguments") && !params.get("arguments").isJsonObject())throw new IllegalArgumentException("arguments 必须为对象");
                    JsonObject arguments=params.has("arguments")?params.getAsJsonObject("arguments"):new JsonObject();
                    try{
                        if(!NAMES.contains(name))throw new IllegalArgumentException("未知工具: "+name);
                        JsonObject data=backend.call(name,arguments);String text=data.toString();
                        if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>524288)throw new IllegalArgumentException("结果超过 512 KiB，请缩小范围");
                        result.add("structuredContent",data);result.add("content",content(text));result.addProperty("isError",false);
                    }catch(Exception|LinkageError e){result.add("content",content(e instanceof IllegalArgumentException?e.getMessage():"工具执行失败；请检查配置和实际验证范围"));result.addProperty("isError",true);}
                }
                default -> {return new Reply(200,error(id,-32601,"不支持 method: "+method));}
            }
            JsonObject response=new JsonObject();response.addProperty("jsonrpc","2.0");response.add("id",id);response.add("result",result);return new Reply(200,response);
        }catch(IllegalArgumentException e){return new Reply(200,error(id,-32602,e.getMessage()));}
        catch(IOException|JsonParseException e){return new Reply(400,error(id,-32700,"JSON 解析失败"));}
    }
    private static JsonArray content(String text){JsonObject item=new JsonObject();item.addProperty("type","text");item.addProperty("text",text);JsonArray array=new JsonArray();array.add(item);return array;}
    private static JsonObject error(JsonElement id,int code,String message){JsonObject error=new JsonObject();error.addProperty("code",code);error.addProperty("message",message);JsonObject response=new JsonObject();response.addProperty("jsonrpc","2.0");response.add("id",id);response.add("error",error);return response;}
    public static String string(JsonObject object,String key,boolean required){
        JsonElement value=object.get(key);if(value==null || value.isJsonNull()){if(required)throw new IllegalArgumentException("缺少 "+key);return "";}
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException(key+" 必须为字符串");
        String text=value.getAsString();if(text.length()>4096)throw new IllegalArgumentException(key+" 超出长度上限");return text;
    }
    private static boolean bounded(String text){int depth=0;boolean quote=false,escape=false;for(char c:text.toCharArray()){
        if(quote){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quote=false;}
        else if(c=='"')quote=true;else if(c=='{' || c=='['){if(++depth>64)return false;}else if(c=='}' || c==']')depth--;
    }return true;}
    private static final Set<String> NAMES=Set.of("list_data_sources","list_tables","get_table_columns","find_mapper_xml","find_mapper_interface","list_mapper_statements","validate_mybatis_mapper","run_mybatis_sql");
    public static JsonArray tools(){
        JsonArray tools=new JsonArray();
        add(tools,"list_data_sources","列出可用数据源，不返回凭证",Map.of(),List.of(),true);
        add(tools,"list_tables","读取精确数据源/schema 的表列表（最多 500）",Map.of("dataSource","string","schema","string","search","string","limit","integer"),List.of("dataSource"),true);
        add(tools,"get_table_columns","读取字段和主键；同名表需要 schema",Map.of("dataSource","string","schema","string","table","string"),List.of("dataSource","table"),true);
        add(tools,"find_mapper_xml","按 namespace 索引查找 Mapper XML",Map.of("namespace","string"),List.of("namespace"),true);
        add(tools,"find_mapper_interface","按 namespace 查找 Java/Kotlin Mapper 接口",Map.of("namespace","string"),List.of("namespace"),true);
        add(tools,"list_mapper_statements","列出指定 XML 的语句",Map.of("path","string"),List.of("path"),true);
        add(tools,"validate_mybatis_mapper","运行当前映射检查并报告已执行/跳过范围",Map.of("path","string"),List.of("path"),true);
        add(tools,"run_mybatis_sql","按 JSON 参数渲染 SQL；可选只读执行/事务回滚，不支持提交",Map.of("path","string","statementId","string","parameters","object","execute","boolean","dataSource","string","dryRun","boolean","maxRows","integer","timeoutSeconds","integer"),List.of("path","statementId"),false);
        return tools;
    }
    private static void add(JsonArray tools,String name,String description,Map<String,String> properties,List<String> required,boolean readOnly){
        JsonObject tool=new JsonObject();tool.addProperty("name",name);tool.addProperty("description",description);JsonObject schema=new JsonObject(),props=new JsonObject();schema.addProperty("type","object");schema.addProperty("additionalProperties",false);
        properties.forEach((key,type)->{JsonObject property=new JsonObject();property.addProperty("type",type);props.add(key,property);});schema.add("properties",props);schema.add("required",new Gson().toJsonTree(required));tool.add("inputSchema",schema);
        JsonObject annotations=new JsonObject();annotations.addProperty("readOnlyHint",readOnly);annotations.addProperty("destructiveHint",!readOnly);tool.add("annotations",annotations);tools.add(tool);
    }
}
