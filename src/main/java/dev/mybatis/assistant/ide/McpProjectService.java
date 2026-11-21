package dev.mybatis.assistant.ide;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlTag;
import dev.mybatis.assistant.core.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Project-owned token and bounded calls; no listener, scheduled task or executor is created. */
public final class McpProjectService implements Disposable,McpProtocol.Backend {
    private final Project project;
    private final String token;
    private final AtomicInteger active=new AtomicInteger();
    private volatile boolean disposed;
    public McpProjectService(Project project){this.project=project;byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public boolean accepts(String supplied){return !disposed && AssistantSettings.get(project).mcpEnabled && MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
    public String clientConfig(int port){return "{\n  \"mcpServers\": {\n    \"mybatis-assistant\": {\n      \"type\": \"http\",\n      \"url\": \"http://127.0.0.1:"+port+"/mybatis-assistant/mcp\",\n      \"headers\": {\"Authorization\": \"Bearer "+token+"\"}\n    }\n  }\n}";}
    public String codexConfig(int port){return "[mcp_servers.mybatis_assistant]\nurl = \"http://127.0.0.1:"+port+"/mybatis-assistant/mcp\"\nhttp_headers = { Authorization = \"Bearer "+token+"\" }\n";}
    public boolean acquire(){if(active.incrementAndGet()>4){active.decrementAndGet();return false;}return true;}
    public void release(){active.decrementAndGet();}
    @Override public void dispose(){disposed=true;}
    @Override public JsonObject call(String name,JsonObject args)throws Exception{
        if(disposed || project.isDisposed() || !AssistantSettings.get(project).mcpEnabled)throw new IllegalArgumentException("此项目 MCP 已关闭");
        if(Set.of("find_mapper_xml","find_mapper_interface","list_mapper_statements","validate_mybatis_mapper","run_mybatis_sql").contains(name) && com.intellij.openapi.project.DumbService.isDumb(project))throw new IllegalArgumentException("IDEA 正在更新索引，请稍后重试");
        Gson gson=new GsonBuilder().serializeNulls().create();JsonObject result=new JsonObject();
        switch(name){
            case "list_data_sources" -> result.add("dataSources",gson.toJsonTree(DatabaseAccess.sources(project)));
            case "find_mapper_xml" -> {
                String namespace=McpProtocol.string(args,"namespace",true);result.add("files",gson.toJsonTree(ReadAction.compute(()->MapperModel.xmls(project,namespace).stream().map(r->r.getContainingFile().getVirtualFile().getPath()).limit(100).toList())));
            }
            case "find_mapper_interface" -> {
                String namespace=McpProtocol.string(args,"namespace",true);JsonObject data=ReadAction.compute(()->{
                    PsiClass cls=MapperModel.classByName(project,namespace);JsonObject found=new JsonObject();if(cls==null || !cls.isInterface())return found;
                    found.addProperty("qualifiedName",cls.getQualifiedName());PsiElement source=cls.getNavigationElement();if(source.getContainingFile()!=null && source.getContainingFile().getVirtualFile()!=null)found.addProperty("path",source.getContainingFile().getVirtualFile().getPath());return found;
                });result.add("mapper",data);
            }
            case "list_mapper_statements","validate_mybatis_mapper" -> {
                PsiFile file=MapperSnapshot.file(project,McpProtocol.string(args,"path",true));
                if(name.equals("validate_mybatis_mapper"))return gson.toJsonTree(ReadAction.compute(()->MapperDiagnostics.inspect(file))).getAsJsonObject();
                result.add("statements",gson.toJsonTree(ReadAction.compute(()->{XmlTag root=MapperModel.root(file);if(root==null)throw new IllegalArgumentException("需要 Mapper XML");List<Map<String,String>> statements=new ArrayList<>();for(XmlTag tag:root.getSubTags())if(MapperModel.isStatement(tag)){if(statements.size()>=2000)throw new IllegalArgumentException("语句数量超过 2000");statements.add(Map.of("id",Objects.requireNonNullElse(tag.getAttributeValue("id"),""),"kind",tag.getName()));}return statements;})));
            }
            case "list_tables","get_table_columns" -> {
                databasePermission();String source=DatabaseAccess.sourceId(project,McpProtocol.string(args,"dataSource",true)),schema=McpProtocol.string(args,"schema",false);if(schema.isBlank())schema=null;
                try(JdbcSession session=DatabaseAccess.open(project,source)){
                    if(name.equals("list_tables"))result.add("tables",gson.toJsonTree(JdbcMetadata.tables(session.connection(),schema,McpProtocol.string(args,"search",false),number(args,"limit",100,1,500))));
                    else result.add("columns",gson.toJsonTree(JdbcMetadata.columns(session.connection(),schema,McpProtocol.string(args,"table",true))));
                }catch(Exception e){throw new IllegalArgumentException(DatabaseAccess.error(e));}
            }
            case "run_mybatis_sql" -> {
                JsonElement parameters=args.has("parameters")?args.get("parameters"):new JsonObject();if(!parameters.isJsonObject())throw new IllegalArgumentException("parameters 必须为 JSON 对象");
                var rendered=MapperSnapshot.requested(project,McpProtocol.string(args,"path",true),McpProtocol.string(args,"statementId",true),parameters.toString()).render();
                result.add("rendered",gson.toJsonTree(rendered));result.addProperty("executed",false);
                if(flag(args,"execute",false)){
                    databasePermission();if(!rendered.notes().isEmpty())throw new IllegalArgumentException("渲染存在跳过项，只允许预览");
                    SqlExecution.Mode mode=flag(args,"dryRun",true)?SqlExecution.Mode.ROLLBACK:SqlExecution.Mode.READ_ONLY;
                    SqlExecutionPolicy.validate(rendered.sql(),mode);boolean query;try{SqlExecutionPolicy.validate(rendered.sql(),SqlExecution.Mode.READ_ONLY);query=true;}catch(IllegalArgumentException e){query=false;}
                    if(!query && !AssistantSettings.get(project).mcpDmlRollback)throw new IllegalArgumentException("MCP DML 回滚试跑未开启");
                    String source=DatabaseAccess.sourceId(project,McpProtocol.string(args,"dataSource",true));
                    if(DatabaseAccess.sources(project).stream().anyMatch(s->s.id().equals(source) && s.readOnly()))mode=SqlExecution.Mode.READ_ONLY;
                    try(JdbcSession session=DatabaseAccess.open(project,source)){result.add("execution",gson.toJsonTree(SqlExecution.execute(session.connection(),rendered.sql(),rendered.parameters(),new SqlExecution.Options(mode,number(args,"maxRows",100,1,1000),number(args,"timeoutSeconds",15,1,60)))));result.addProperty("executed",true);}
                    catch(Exception e){throw new IllegalArgumentException(DatabaseAccess.error(e));}
                }
            }
            default -> throw new IllegalArgumentException("未知工具");
        }return result;
    }
    private void databasePermission(){if(!AssistantSettings.get(project).mcpDatabaseRead)throw new IllegalArgumentException("请先在插件设置中开启 MCP 数据库访问");}
    static boolean flag(JsonObject args,String key,boolean fallback){if(!args.has(key))return fallback;if(!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isBoolean())throw new IllegalArgumentException(key+" 必须为 boolean");return args.get(key).getAsBoolean();}
    static int number(JsonObject args,String key,int fallback,int min,int max){if(!args.has(key))return fallback;try{int value=args.get(key).getAsBigDecimal().intValueExact();if(value<min || value>max)throw new IllegalArgumentException();return value;}catch(Exception e){throw new IllegalArgumentException(key+" 超出范围 "+min+"–"+max);}}
}
