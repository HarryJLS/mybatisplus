package dev.mybatis.assistant.ide;

import com.google.gson.*;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.jetbrains.ide.*;

/** Exercises the actual IDE HTTP dispatcher, with an isolated fixture project context. */
public final class McpHttpIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private int port;private String token;
    private static final String TEST_PATH="/mybatis-assistant/mcp-integration-test";
    @Override protected void setUp()throws Exception{
        super.setUp();AssistantSettings.get(getProject()).mcpEnabled=true;AssistantSettings.get(getProject()).mcpDatabaseRead=false;AssistantSettings.get(getProject()).jdbcUrl="";
        var service=getProject().getService(McpProjectService.class);token=JsonParser.parseString(service.clientConfig(63342)).getAsJsonObject().getAsJsonObject("mcpServers").getAsJsonObject("mybatis-assistant").getAsJsonObject("headers").get("Authorization").getAsString();
        ExtensionPointName.<HttpRequestHandler>create("com.intellij.httpRequestHandler").getPoint().registerExtension(new McpHttpHandler(TEST_PATH,()->new com.intellij.openapi.project.Project[]{getProject()}),getTestRootDisposable());
        port=BuiltInServerManager.getInstance().waitForStart().getPort();
    }
    @Override protected void tearDown()throws Exception{try{AssistantSettings.get(getProject()).mcpEnabled=false;}finally{super.tearDown();}}
    private record Response(int status,String body) {}
    private Response request(String method,String bearer,String body,String origin)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+TEST_PATH).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(10000);connection.setRequestMethod(method);
        connection.setRequestProperty("Accept","application/json, text/event-stream");connection.setRequestProperty("Content-Type","application/json");if(bearer!=null)connection.setRequestProperty("Authorization",bearer);if(origin!=null)connection.setRequestProperty("Origin",origin);
        if(body!=null){connection.setDoOutput(true);try(var output=connection.getOutputStream()){output.write(body.getBytes(StandardCharsets.UTF_8));}}
        int status=connection.getResponseCode();String text="";var stream=status>=400?connection.getErrorStream():connection.getInputStream();if(stream!=null)try(stream){text=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}connection.disconnect();return new Response(status,text);
    }
    public void testAuthenticatedInitializeAndTools()throws Exception{
        Response init=request("POST",token,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",null);assertEquals(init.body(),200,init.status());assertEquals("2025-06-18",JsonParser.parseString(init.body()).getAsJsonObject().getAsJsonObject("result").get("protocolVersion").getAsString());
        Response tools=request("POST",token,"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",null);assertEquals(200,tools.status());assertEquals(8,JsonParser.parseString(tools.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").size());
        Response denied=request("POST",token,"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_tables\",\"arguments\":{\"dataSource\":\"manual\"}}}",null);assertEquals(200,denied.status());assertTrue(JsonParser.parseString(denied.body()).getAsJsonObject().getAsJsonObject("result").get("isError").getAsBoolean());
    }
    public void testAuthenticationGetAndMalformedRequest()throws Exception{
        assertEquals(401,request("POST","Bearer wrong","{}",null).status());assertEquals(405,request("GET",token,null,null).status());assertEquals(400,request("POST",token,"[]",null).status());assertTrue(request("POST",token,"{}","https://evil.example").status()>=400);
    }
}
