package com.mizius.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientLogsTest {
    @TempDir Path directory;
    private Path file;
    private ClientLogs logs;
    @BeforeEach void setup() { file=directory.resolve("latest.log"); logs=new ClientLogs(file); }
    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private List<String> lines(JsonObject result) {
        return result.getAsJsonArray("lines").asList().stream().map(JsonElement::getAsString).toList();
    }
    @Test void returnsLatestLinesInChronologicalOrder() throws Exception {
        Files.writeString(file,"first\nsecond\nthird\n");
        JsonObject result=logs.read(args("{\"limit\":2}"));
        assertEquals(List.of("second","third"),lines(result));
        assertTrue(result.get("truncated").getAsBoolean());
        assertEquals(2,result.get("returned").getAsInt());
    }
    @Test void queriesUseCaseInsensitiveLiteralOrMatching() throws Exception {
        Files.writeString(file,"[INFO] start\n[WARN] Äpfel 🍎\n[ERROR] failure\nplain error text\n");
        JsonObject result=logs.read(args("{\"queries\":[\"[warn]\",\"[error]\"],\"limit\":100}"));
        assertEquals(List.of("[WARN] Äpfel 🍎","[ERROR] failure"),lines(result));
        assertFalse(result.get("truncated").getAsBoolean());
        assertEquals(100,logs.read(new JsonObject()).get("limit").getAsInt());
    }
    @Test void handlesEmptyAndMissingLogs() throws Exception {
        assertThrows(FileNotFoundException.class,()->logs.read(new JsonObject()));
        Files.writeString(file,"");
        JsonObject result=logs.read(new JsonObject());
        assertTrue(lines(result).isEmpty()); assertFalse(result.get("truncated").getAsBoolean());
    }
    @Test void rejectsInvalidLimitsAndQueries() throws Exception {
        Files.writeString(file,"line\n");
        for(String invalid:List.of("{\"limit\":0}","{\"limit\":1001}","{\"limit\":2.5}","{\"limit\":\"2\"}","{\"queries\":\"error\"}","{\"queries\":[3]}"))
            assertThrows(IllegalArgumentException.class,()->logs.read(args(invalid)));
        JsonObject oversized=new JsonObject(); JsonArray queries=new JsonArray(); for(int i=0;i<17;i++) queries.add("x"); oversized.add("queries",queries);
        assertThrows(IllegalArgumentException.class,()->logs.read(oversized));
        assertThrows(IllegalArgumentException.class,()->logs.read(args("{\"queries\":[\""+"x".repeat(257)+"\"]}")));
    }
    @Test void scanIsBoundedAndSkipsPartialBoundaryLines() throws Exception {
        try(RandomAccessFile log=new RandomAccessFile(file.toFile(),"rw")) {
            log.writeBytes("[ERROR] old match\n");
            log.setLength(ClientLogs.MAX_SCAN_BYTES+100L);
            log.seek(log.length()-20);
            log.writeBytes("\n[ERROR] new match\n");
        }
        JsonObject result=logs.read(args("{\"queries\":[\"[error]\"]}"));
        assertEquals(List.of("[ERROR] new match"),lines(result));
        assertTrue(result.get("truncated").getAsBoolean());
        assertEquals(ClientLogs.MAX_SCAN_BYTES,result.get("scanned_bytes").getAsInt());
    }
    @Test void followsCurrentLogAfterRotation() throws Exception {
        Files.writeString(file,"old\n"); assertEquals(List.of("old"),lines(logs.read(new JsonObject())));
        Files.move(file,directory.resolve("previous.log"));
        Files.writeString(file,"new\n"); assertEquals(List.of("new"),lines(logs.read(new JsonObject())));
    }
    @Test void logsRemainAvailableWhileGameActionIsBusy() throws Exception {
        Files.writeString(file,"[WARN] client message\n");
        CompletableFuture<JsonObject> pending=new CompletableFuture<>();
        CountDownLatch entered=new CountDownLatch(1);
        try(McpServer server=new McpServer((name,a)->{ entered.countDown(); return pending; },0,"",file)) {
            server.start();
            HttpClient http=HttpClient.newHttpClient(); URI endpoint=URI.create("http://127.0.0.1:"+server.port()+"/mcp");
            HttpRequest action=HttpRequest.newBuilder(endpoint).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"key\"}}")).build();
            CompletableFuture<HttpResponse<String>> busy=http.sendAsync(action,HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            HttpRequest read=HttpRequest.newBuilder(endpoint).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_logs\",\"arguments\":{\"queries\":[\"warn\"],\"limit\":10}}}")).build();
            HttpResponse<String> response=http.send(read,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            JsonObject log=JsonParser.parseString(JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
            assertEquals(List.of("[WARN] client message"),lines(log));
            assertFalse(busy.isDone());
            pending.complete(McpServer.text("ok")); assertEquals(200,busy.get(2,TimeUnit.SECONDS).statusCode());
        }
    }
}
