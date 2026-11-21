package dev.mybatis.assistant.core;

import com.google.gson.*;
import java.util.*;

public final class RuntimeCoreTests {
    private static int count;
    private static void equal(Object a,Object b){count++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    private static void rejected(String sql,SqlExecution.Mode mode){count++;try{SqlExecutionPolicy.validate(sql,mode);}catch(IllegalArgumentException expected){return;}throw new AssertionError("SQL should have been refused: "+sql);}
    public static void main(String[] args){
        equal(SqlExecutionPolicy.validate("-- comment\nSELECT ? AS id;",SqlExecution.Mode.READ_ONLY),"-- comment\nSELECT ? AS id");
        equal(SqlExecutionPolicy.validate("INSERT INTO users (name) VALUES (?)",SqlExecution.Mode.ROLLBACK),"INSERT INTO users (name) VALUES (?)");
        equal(SqlExecutionPolicy.validate("SELECT 'a; b', \"x\" FROM users",SqlExecution.Mode.READ_ONLY),"SELECT 'a; b', \"x\" FROM users");
        rejected("SELECT 1; DELETE FROM users",SqlExecution.Mode.ROLLBACK);rejected("UPDATE users SET name=?",SqlExecution.Mode.READ_ONLY);
        rejected("WITH q AS (DELETE FROM users RETURNING *) SELECT * FROM q",SqlExecution.Mode.READ_ONLY);
        rejected("SELECT * FROM users FOR UPDATE",SqlExecution.Mode.READ_ONLY);rejected("SELECT name INTO outfile 'x' FROM users",SqlExecution.Mode.READ_ONLY);
        rejected("CREATE TABLE test (id int)",SqlExecution.Mode.COMMIT);rejected("CALL a()",SqlExecution.Mode.ROLLBACK);rejected("SELECT ${x}",SqlExecution.Mode.READ_ONLY);
        rejected("SELECT 'unclosed",SqlExecution.Mode.READ_ONLY);rejected("SELECT 1 /*! DELETE FROM users */",SqlExecution.Mode.READ_ONLY);
        int[] calls={0};McpProtocol.Backend backend=(name,params)->{calls[0]++;JsonObject value=new JsonObject();value.addProperty("ok",true);return value;};
        var init=McpProtocol.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}",backend);
        equal(init.status(),200);equal(init.body().getAsJsonObject("result").get("protocolVersion").getAsString(),"2025-06-18");equal(calls[0],0);
        equal(McpProtocol.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",backend).status(),202);
        var list=McpProtocol.handle("{\"jsonrpc\":\"2.0\",\"id\":\"tools\",\"method\":\"tools/list\"}",backend);equal(list.body().getAsJsonObject("result").getAsJsonArray("tools").size(),8);
        var result=McpProtocol.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_data_sources\",\"arguments\":{}}}",backend);
        equal(calls[0],1);equal(result.body().getAsJsonObject("result").getAsJsonObject("structuredContent").get("ok").getAsBoolean(),true);
        equal(McpProtocol.handle("[]",backend).status(),400);equal(McpProtocol.handle("{bad json}",backend).body().getAsJsonObject("error").get("code").getAsInt(),-32700);
        equal(McpProtocol.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unknown\"}",backend).body().getAsJsonObject("error").get("code").getAsInt(),-32601);
        var failure=McpProtocol.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_tables\"}}",(name,params)->{throw new IllegalArgumentException("not configured");});
        equal(failure.body().getAsJsonObject("result").get("isError").getAsBoolean(),true);
        equal(McpProtocol.handle("[".repeat(65)+"0"+"]".repeat(65),backend).status(),413);
        System.out.println("PASS: "+count+" runtime/MCP core assertions");
    }
}
