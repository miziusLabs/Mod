package com.mizius.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
    private McpServer server;
    private final List<String> calls=new CopyOnWriteArrayList<>();
    private final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void start() throws Exception {
        server=new McpServer((name,args) -> {
            calls.add(name);
            return CompletableFuture.completedFuture(name.equals("type") ? McpServer.failure("no screen") : McpServer.text("ok"));
        },0,"");
        server.start();
    }
    @AfterEach void stop() { server.close(); }
    private HttpResponse<String> post(String body,String... headers) throws Exception {
        HttpRequest.Builder r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/mcp"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        for(int i=0;i<headers.length;i+=2) r.header(headers[i],headers[i+1]);
        return client.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonObject result(String body) throws Exception { return JsonParser.parseString(post(body).body()).getAsJsonObject().getAsJsonObject("result"); }
    @Test void listsAllModTools() throws Exception {
        JsonArray list=result("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").getAsJsonArray("tools");
        Set<String> names=new HashSet<>(); for(JsonElement t:list) names.add(t.getAsJsonObject().get("name").getAsString());
        assertEquals(ToolSchemas.NAMES,names);
        assertFalse(names.contains("start"));
    }
    @Test void supportsLegacyHandshakeAndModernDiscovery() throws Exception {
        assertEquals("2025-11-25",result("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}").get("protocolVersion").getAsString());
        JsonObject discovered=result("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"server/discover\"}");
        assertEquals("complete",discovered.get("resultType").getAsString());
        assertTrue(discovered.has("supportedVersions"));
        assertTrue(discovered.getAsJsonObject("_meta").has("io.modelcontextprotocol/serverInfo"));
    }
    @Test void rejectsBrowserOriginMalformedAndOversizedMessages() throws Exception {
        assertEquals(403,post("{}","Origin","https://example.com").statusCode());
        assertEquals(400,post("{").statusCode());
        assertEquals(400,post("[]").statusCode());
        assertEquals(413,post(" ".repeat(1_048_577)).statusCode());
        assertEquals(400,post("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}").statusCode());
    }
    @Test void notificationsDoNotExecuteActions() throws Exception {
        assertEquals(202,post("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"key\"}}").statusCode());
        assertTrue(calls.isEmpty());
    }
    @Test void batchesAreOrderedAndStopAtFailure() throws Exception {
        JsonObject r=result("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"batch\",\"arguments\":{\"actions\":[{\"tool\":\"key\"},{\"tool\":\"type\"},{\"tool\":\"screenshot\"}]}}}");
        assertTrue(r.get("isError").getAsBoolean()); assertEquals(List.of("key","type"),calls);
    }
    @Test void rejectsNestedBatchesBeforeExecution() throws Exception {
        JsonObject r=result("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"batch\",\"arguments\":{\"actions\":[{\"tool\":\"key\"},{\"tool\":\"batch\"}]}}}");
        assertTrue(r.get("isError").getAsBoolean()); assertTrue(calls.isEmpty());
    }
    @Test void validatesModernRoutingAndAddsResultMetadata() throws Exception {
        String body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}";
        assertEquals(400,post(body,"MCP-Protocol-Version","2026-07-28","Mcp-Method","ping").statusCode());
        HttpResponse<String> response=post(body,"MCP-Protocol-Version","2026-07-28","Mcp-Method","tools/list");
        assertEquals(200,response.statusCode());
        assertEquals("complete",JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result").get("resultType").getAsString());
    }
    @Test void enforcesOptionalBearerToken() throws Exception {
        server.close(); server=new McpServer((n,a)->CompletableFuture.completedFuture(McpServer.text("ok")),0,"test-secret"); server.start();
        assertEquals(401,post("{}").statusCode());
        assertEquals(200,post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}","Authorization","Bearer test-secret").statusCode());
    }
}
