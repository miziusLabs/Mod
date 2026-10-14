package com.mizius.mcp;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Small, stateless MCP HTTP transport using Minecraft's existing Gson dependency. */
public final class McpServer implements AutoCloseable {
    public interface Tools { CompletableFuture<JsonObject> call(String name, JsonObject arguments); }
    private final Tools tools;
    private final ClientLogs logs;
    private final HttpServer http;
    private final ExecutorService workers;
    private final Semaphore actions = new Semaphore(1, true);
    private final String token;
    private static final int MAX_BODY = 1_048_576;
    private static final Set<String> VERSIONS = Set.of("2025-03-26","2025-06-18","2025-11-25","2026-07-28");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public McpServer(Tools tools) throws IOException {
        this(tools, Integer.getInteger("minecraft.mcp.port",8765), System.getProperty("minecraft.mcp.token",Objects.toString(System.getenv("MINECRAFT_MCP_TOKEN"),"")));
    }
    public McpServer(Tools tools,Path gameDirectory) throws IOException {
        this(tools,Integer.getInteger("minecraft.mcp.port",8765),System.getProperty("minecraft.mcp.token",Objects.toString(System.getenv("MINECRAFT_MCP_TOKEN"),"")),gameDirectory.resolve("logs/latest.log"));
    }
    McpServer(Tools tools, int port, String token) throws IOException {
        this(tools,port,token,Path.of("logs/latest.log"));
    }
    McpServer(Tools tools,int port,String token,Path logFile) throws IOException {
        this.tools=tools; this.token=token; this.logs=new ClientLogs(logFile);
        http=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);
        workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r -> {
            Thread t=new Thread(r,"minecraft-mcp-http"); t.setDaemon(true); return t;
        },new ThreadPoolExecutor.AbortPolicy());
        http.setExecutor(workers);
        http.createContext("/mcp",this::handle);
    }
    public int port() { return http.getAddress().getPort(); }
    public void start() { http.start(); }
    @Override public void close() { http.stop(0); workers.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String expected="127.0.0.1:"+port(), host=exchange.getRequestHeaders().getFirst("Host");
            if (!(expected.equals(host) || ("localhost:"+port()).equals(host))) { send(exchange,403,null); return; }
            // Browser origins are intentionally unsupported. Native MCP clients omit Origin.
            if (exchange.getRequestHeaders().containsKey("Origin")) { send(exchange,403,null); return; }
            if (!token.isEmpty() && !java.security.MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),
                    Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"").getBytes(StandardCharsets.UTF_8))) {
                send(exchange,401,null); return;
            }
            if (!exchange.getRequestURI().getPath().equals("/mcp")) { send(exchange,404,null); return; }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow","POST"); send(exchange,405,null); return;
            }
            String type=exchange.getRequestHeaders().getFirst("Content-Type");
            if (type==null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) { send(exchange,415,null); return; }
            byte[] bytes=exchange.getRequestBody().readNBytes(MAX_BODY+1);
            if(bytes.length>MAX_BODY) { send(exchange,413,null); return; }
            JsonObject request;
            try {
                JsonElement parsed=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) { send(exchange,400,error(JsonNull.INSTANCE,-32600,"Expected one JSON-RPC request")); return; }
                request=parsed.getAsJsonObject();
            } catch(JsonParseException e) { send(exchange,400,error(JsonNull.INSTANCE,-32700,"Invalid JSON")); return; }
            JsonElement id=request.has("id") ? request.get("id") : JsonNull.INSTANCE;
            if (!request.has("jsonrpc") || !request.get("jsonrpc").isJsonPrimitive() || !request.get("jsonrpc").getAsString().equals("2.0")
                || !request.has("method") || !request.get("method").isJsonPrimitive() || !request.get("method").getAsJsonPrimitive().isString()
                || request.has("params") && !request.get("params").isJsonObject()
                || request.has("id") && (!id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean())) {
                send(exchange,400,error(JsonNull.INSTANCE,-32600,"Invalid JSON-RPC request")); return;
            }
            String method=request.get("method").getAsString();
            JsonObject params=request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
            String version=exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version!=null && !VERSIONS.contains(version)) {
                JsonObject response=error(id,-32001,"Unsupported protocol version");
                JsonObject data=new JsonObject(); JsonArray versions=new JsonArray(); VERSIONS.stream().sorted().forEach(versions::add);
                data.add("supportedProtocolVersions",versions); response.getAsJsonObject("error").add("data",data);
                send(exchange,400,response); return;
            }
            if ("2026-07-28".equals(version)) {
                JsonObject meta=params.has("_meta") && params.get("_meta").isJsonObject() ? params.getAsJsonObject("_meta") : new JsonObject();
                if (!matching(meta,"io.modelcontextprotocol/protocolVersion",version)
                    || !Objects.equals(method,exchange.getRequestHeaders().getFirst("Mcp-Method"))
                    || method.equals("tools/call") && (!params.has("name") || !matching(params,"name",exchange.getRequestHeaders().getFirst("Mcp-Name")))) {
                    send(exchange,400,error(id,-32602,"MCP routing metadata mismatch")); return;
                }
            }
            if (!request.has("id")) { send(exchange,202,null); return; }
            try {
                JsonObject result=switch(method) {
                    case "initialize" -> initialize(params);
                    case "server/discover" -> discover();
                    case "ping" -> new JsonObject();
                    case "tools/list" -> list();
                    case "tools/call" -> callTool(params);
                    default -> null;
                };
                if(result==null) { send(exchange,404,error(id,-32601,"Method not found")); return; }
                if ("2026-07-28".equals(version)) modern(result);
                JsonObject response=new JsonObject(); response.addProperty("jsonrpc","2.0"); response.add("id",id); response.add("result",result);
                send(exchange,200,response);
            } catch (IllegalArgumentException | IllegalStateException e) { send(exchange,200,error(id,-32602,e.getMessage())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); send(exchange,503,error(id,-32603,"Server stopping")); }
            catch (Exception e) { send(exchange,500,error(id,-32603,"Internal server error")); }
        }
    }
    private static boolean matching(JsonObject object,String key,String value) {
        return value!=null && object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isString() && value.equals(object.get(key).getAsString());
    }
    private static JsonObject initialize(JsonObject params) {
        String requested=params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-11-25";
        JsonObject result=new JsonObject();
        result.addProperty("protocolVersion",VERSIONS.contains(requested) && !requested.equals("2026-07-28") ? requested : "2025-11-25");
        result.add("capabilities",capabilities()); result.add("serverInfo",info());
        result.addProperty("instructions","Coordinates are framebuffer pixels. Release held inputs when done. Inputs expire after 30 seconds without tool calls.");
        return result;
    }
    private static JsonObject discover() {
        JsonObject r=new JsonObject(); JsonArray versions=new JsonArray(); VERSIONS.stream().sorted().forEach(versions::add);
        r.add("supportedVersions",versions); r.add("capabilities",capabilities());
        r.addProperty("ttlMs",0); r.addProperty("cacheScope","private"); modern(r); return r;
    }
    private static void modern(JsonObject result) {
        result.addProperty("resultType","complete");
        JsonObject meta=new JsonObject(); meta.add("io.modelcontextprotocol/serverInfo",info()); result.add("_meta",meta);
    }
    private static JsonObject capabilities() { JsonObject c=new JsonObject(); c.add("tools",new JsonObject()); return c; }
    private static JsonObject info() { JsonObject i=new JsonObject(); i.addProperty("name","minecraft-mcp"); i.addProperty("version","1.0.0"); return i; }
    private JsonObject callTool(JsonObject params) throws InterruptedException {
        String name=ClientControl.string(params,"name",64);
        if (!ToolSchemas.NAMES.contains(name)) throw new IllegalArgumentException("Unknown tool: "+name);
        if (params.has("arguments") && !params.get("arguments").isJsonObject()) throw new IllegalArgumentException("arguments must be an object");
        JsonObject args=params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        if (name.equals("get_logs")) return invoke(name,args);
        if (!actions.tryAcquire(1,TimeUnit.SECONDS)) return failure("Another action sequence is running. Retry after it completes.");
        try {
            if (!name.equals("batch")) return invoke(name,args);
            if (!args.has("actions") || !args.get("actions").isJsonArray()) return failure("actions must be an array");
            JsonArray sequence=args.getAsJsonArray("actions");
            if(sequence.isEmpty() || sequence.size()>128) return failure("batch must contain 1 to 128 actions");
            // Validate sequence structure before any game interaction.
            long duration=0;
            int screenshots=0;
            for(JsonElement item:sequence) {
                if(!item.isJsonObject()) return failure("Each action must be an object");
                JsonObject action=item.getAsJsonObject();
                String tool=ClientControl.string(action,"tool",64);
                if(!ToolSchemas.NAMES.contains(tool) || tool.equals("batch")) return failure("Invalid batch tool: "+tool);
                if (action.has("arguments") && !action.get("arguments").isJsonObject()) return failure("Action arguments must be an object");
                duration+=action.has("delay_ms") ? ClientControl.integer(action,"delay_ms",0,10000) : 0;
                if (tool.equals("drag")) duration+=action.has("arguments") && action.getAsJsonObject("arguments").has("duration_ms") ? ClientControl.integer(action.getAsJsonObject("arguments"),"duration_ms",50,10000) : 250;
                if(tool.equals("click") || tool.equals("key")) duration+=100;
                if(tool.equals("screenshot")) screenshots++;
            }
            if(duration>20000) return failure("Batch delays and drag durations must total at most 20000 ms");
            if(screenshots>8) return failure("Batch may contain at most 8 screenshots");
            JsonArray content=new JsonArray(); int index=0;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            for(JsonElement item:sequence) {
                JsonObject action=item.getAsJsonObject();
                if(action.has("delay_ms")) Thread.sleep(ClientControl.integer(action,"delay_ms",0,10000));
                JsonObject r=invoke(action.get("tool").getAsString(),action.has("arguments") ? action.getAsJsonObject("arguments") : new JsonObject(),deadline);
                for(JsonElement c:r.getAsJsonArray("content")) content.add(c);
                if(r.has("isError") && r.get("isError").getAsBoolean()) {
                    content.add(text("Batch stopped at action "+index).getAsJsonArray("content").get(0));
                    JsonObject failed=new JsonObject(); failed.add("content",content); failed.addProperty("isError",true); return failed;
                }
                index++;
            }
            JsonObject r=new JsonObject(); r.add("content",content); return r;
        } finally { actions.release(); }
    }
    private JsonObject invoke(String name,JsonObject args) throws InterruptedException {
        return invoke(name,args,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
    }
    private JsonObject invoke(String name,JsonObject args,long deadline) throws InterruptedException {
        long remaining=Math.min(TimeUnit.SECONDS.toNanos(15),deadline-System.nanoTime());
        if (remaining<=0) return failure("Batch exceeded its 30 second execution limit");
        if(name.equals("get_logs")) {
            try { return text(logs.read(args)); }
            catch(IOException | IllegalArgumentException e) { return failure("Could not read client log: "+e.getMessage()); }
        }
        CompletableFuture<JsonObject> future=tools.call(name,args);
        try { return future.get(remaining,TimeUnit.NANOSECONDS); }
        catch(TimeoutException e) { future.cancel(false); return failure("Client action timed out. The window may be minimized or the client busy."); }
        catch(ExecutionException e) { return failure(Objects.toString(e.getCause().getMessage(),"Client action failed")); }
        catch(InterruptedException e) { future.cancel(false); throw e; }
    }
    static JsonObject list() { JsonObject r=new JsonObject(); r.add("tools",ToolSchemas.list()); r.addProperty("ttlMs",60000); r.addProperty("cacheScope","private"); return r; }
    public static JsonObject text(Object value) {
        JsonObject block=new JsonObject(); block.addProperty("type","text"); block.addProperty("text",value instanceof String ? (String)value : GSON.toJson(value));
        JsonArray content=new JsonArray(); content.add(block); JsonObject r=new JsonObject(); r.add("content",content); return r;
    }
    static JsonObject failure(String message) { JsonObject r=text(message); r.addProperty("isError",true); return r; }
    private static JsonObject error(JsonElement id,int code,String message) {
        JsonObject e=new JsonObject(); e.addProperty("code",code); e.addProperty("message",message);
        JsonObject r=new JsonObject(); r.addProperty("jsonrpc","2.0"); r.add("id",id); r.add("error",e); return r;
    }
    private static void send(HttpExchange exchange,int status,JsonObject body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        if(body==null) { exchange.sendResponseHeaders(status,-1); return; }
        byte[] bytes=GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
    }
}
