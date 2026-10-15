package com.mizius.mcp

import com.google.gson.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.*

/** Small, stateless MCP HTTP transport using Minecraft's existing Gson dependency. */
class McpServer @Throws(IOException::class) constructor(
    private val tools: Tools,
    port: Int,
    private val token: String,
    logs: ClientLogs? = null
) : AutoCloseable {
    fun interface Tools {
        fun call(name: String, arguments: JsonObject): CompletableFuture<JsonObject>
    }

    private val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
    private val logs = logs ?: ClientLogs()
    private val workers: ExecutorService = ThreadPoolExecutor(
        2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        ThreadFactory { runnable -> Thread(runnable, "minecraft-mcp-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val actions = Semaphore(1, true)

    init {
        http.executor = workers
        http.createContext("/mcp", ::handle)
    }

    @Throws(IOException::class)
    constructor(tools: Tools) : this(tools, configuredPort(), configuredToken())

    fun port(): Int = http.address.port
    fun start() = http.start()
    override fun close() {
        http.stop(0)
        workers.shutdownNow()
        logs.close()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val host = exchange.requestHeaders.getFirst("Host")
            if (host != "127.0.0.1:${port()}" && host != "localhost:${port()}") {
                send(exchange, 403, null); return
            }
            // Browser origins are intentionally unsupported. Native MCP clients omit Origin.
            if (exchange.requestHeaders.containsKey("Origin")) { send(exchange, 403, null); return }
            if (token.isNotEmpty() && !MessageDigest.isEqual(
                    "Bearer $token".toByteArray(UTF_8),
                    (exchange.requestHeaders.getFirst("Authorization") ?: "").toByteArray(UTF_8)
                )) { send(exchange, 401, null); return }
            if (exchange.requestURI.path != "/mcp") { send(exchange, 404, null); return }
            if (exchange.requestMethod != "POST") {
                exchange.responseHeaders.set("Allow", "POST")
                send(exchange, 405, null); return
            }
            val type = exchange.requestHeaders.getFirst("Content-Type")
            if (type == null || !type.lowercase(Locale.ROOT).startsWith("application/json")) { send(exchange, 415, null); return }
            val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
            if (bytes.size > MAX_BODY) { send(exchange, 413, null); return }
            val request = try {
                val parsed = JsonParser.parseString(String(bytes, UTF_8))
                if (!parsed.isJsonObject) { send(exchange, 400, error(JsonNull.INSTANCE, -32600, "Expected one JSON-RPC request")); return }
                parsed.asJsonObject
            } catch (e: JsonParseException) { send(exchange, 400, error(JsonNull.INSTANCE, -32700, "Invalid JSON")); return }
            val id = if (request.has("id")) request.get("id") else JsonNull.INSTANCE
            if (!request.has("jsonrpc") || !request.get("jsonrpc").isJsonPrimitive || request.get("jsonrpc").asString != "2.0"
                || !request.has("method") || !request.get("method").isJsonPrimitive || !request.get("method").asJsonPrimitive.isString
                || request.has("params") && !request.get("params").isJsonObject
                || request.has("id") && (!id.isJsonPrimitive || id.asJsonPrimitive.isBoolean)) {
                send(exchange, 400, error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request")); return
            }
            val method = request.get("method").asString
            val params = if (request.has("params")) request.getAsJsonObject("params") else JsonObject()
            val version = exchange.requestHeaders.getFirst("MCP-Protocol-Version")
            if (version != null && version !in VERSIONS) {
                val response = error(id, -32001, "Unsupported protocol version")
                response.getAsJsonObject("error").add("data", JsonObject().apply { add("supportedProtocolVersions", versions()) })
                send(exchange, 400, response); return
            }
            if (version == "2026-07-28") {
                val meta = if (params.has("_meta") && params.get("_meta").isJsonObject) params.getAsJsonObject("_meta") else JsonObject()
                if (!matching(meta, "io.modelcontextprotocol/protocolVersion", version)
                    || method != exchange.requestHeaders.getFirst("Mcp-Method")
                    || method == "tools/call" && (!params.has("name") || !matching(params, "name", exchange.requestHeaders.getFirst("Mcp-Name")))) {
                    send(exchange, 400, error(id, -32602, "MCP routing metadata mismatch")); return
                }
            }
            if (!request.has("id")) { send(exchange, 202, null); return }
            try {
                val result = when (method) {
                    "initialize" -> initialize(params)
                    "server/discover" -> discover()
                    "ping" -> JsonObject()
                    "tools/list" -> list()
                    "tools/call" -> callTool(params)
                    else -> null
                }
                if (result == null) { send(exchange, 404, error(id, -32601, "Method not found")); return }
                if (version == "2026-07-28") modern(result)
                send(exchange, 200, JsonObject().apply {
                    addProperty("jsonrpc", "2.0"); add("id", id); add("result", result)
                })
            } catch (e: IllegalArgumentException) { send(exchange, 200, error(id, -32602, e.message)) }
            catch (e: IllegalStateException) { send(exchange, 200, error(id, -32602, e.message)) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); send(exchange, 503, error(id, -32603, "Server stopping")) }
            catch (e: Exception) { send(exchange, 500, error(id, -32603, "Internal server error")) }
        }
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = ClientControl.string(params, "name", 64)
        require(name in ToolSchemas.NAMES) { "Unknown tool: $name" }
        require(!params.has("arguments") || params.get("arguments").isJsonObject) { "arguments must be an object" }
        val args = if (params.has("arguments")) params.getAsJsonObject("arguments") else JsonObject()
        if (name == "get_logs") return invoke(name, args)
        if (!actions.tryAcquire(1, TimeUnit.SECONDS)) return failure("Another action sequence is running. Retry after it completes.")
        try {
            if (name != "batch") return invoke(name, args)
            if (!args.has("actions") || !args.get("actions").isJsonArray) return failure("actions must be an array")
            val sequence = args.getAsJsonArray("actions")
            if (sequence.isEmpty || sequence.size() > 128) return failure("batch must contain 1 to 128 actions")
            // Validate sequence structure before any game interaction.
            var duration = 0L
            var screenshots = 0
            for (item in sequence) {
                if (!item.isJsonObject) return failure("Each action must be an object")
                val action = item.asJsonObject
                val tool = ClientControl.string(action, "tool", 64)
                if (tool !in ToolSchemas.NAMES || tool == "batch") return failure("Invalid batch tool: $tool")
                if (action.has("arguments") && !action.get("arguments").isJsonObject) return failure("Action arguments must be an object")
                duration += if (action.has("delay_ms")) ClientControl.integer(action, "delay_ms", 0, 10000) else 0
                if (tool == "drag") duration += if (action.has("arguments") && action.getAsJsonObject("arguments").has("duration_ms")) ClientControl.integer(action.getAsJsonObject("arguments"), "duration_ms", 50, 10000) else 250
                if (tool == "click" || tool == "key") duration += 100
                if (tool == "screenshot") screenshots++
            }
            if (duration > 20000) return failure("Batch delays and drag durations must total at most 20000 ms")
            if (screenshots > 8) return failure("Batch may contain at most 8 screenshots")
            val content = JsonArray()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            for ((index, item) in sequence.withIndex()) {
                val action = item.asJsonObject
                if (action.has("delay_ms")) Thread.sleep(ClientControl.integer(action, "delay_ms", 0, 10000).toLong())
                val result = invoke(action.get("tool").asString, if (action.has("arguments")) action.getAsJsonObject("arguments") else JsonObject(), deadline)
                result.getAsJsonArray("content").forEach { content.add(it) }
                if (result.has("isError") && result.get("isError").asBoolean) {
                    content.add(text("Batch stopped at action $index").getAsJsonArray("content").get(0))
                    return JsonObject().apply { add("content", content); addProperty("isError", true) }
                }
            }
            return JsonObject().apply { add("content", content) }
        } finally { actions.release() }
    }

    private fun invoke(name: String, args: JsonObject, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)): JsonObject {
        val remaining = minOf(TimeUnit.SECONDS.toNanos(15), deadline - System.nanoTime())
        if (remaining <= 0) return failure("Batch exceeded its 30 second execution limit")
        if (name == "get_logs") {
            return try { text(logs.read(args)) }
            catch (e: IllegalArgumentException) { failure("Could not query client logs: ${e.message}") }
        }
        val future = tools.call(name, args)
        return try { future.get(remaining, TimeUnit.NANOSECONDS) }
        catch (e: TimeoutException) { future.cancel(false); failure("Client action timed out. The window may be minimized or the client busy.") }
        catch (e: ExecutionException) { failure(e.cause!!.message ?: "Client action failed") }
        catch (e: InterruptedException) { future.cancel(false); throw e }
    }

    companion object {
        private const val MAX_BODY = 1_048_576
        private val VERSIONS = setOf("2025-03-26", "2025-06-18", "2025-11-25", "2026-07-28")
        private val GSON = GsonBuilder().serializeNulls().create()
        private fun configuredPort(): Int = Integer.getInteger("minecraft.mcp.port", 8765)
        private fun configuredToken(): String = System.getProperty("minecraft.mcp.token", System.getenv("MINECRAFT_MCP_TOKEN") ?: "")
        private fun versions(): JsonArray = JsonArray().apply { VERSIONS.sorted().forEach { add(it) } }
        private fun matching(obj: JsonObject, key: String, value: String?): Boolean =
            value != null && obj.has(key) && obj.get(key).isJsonPrimitive && obj.get(key).asJsonPrimitive.isString && value == obj.get(key).asString

        private fun initialize(params: JsonObject): JsonObject {
            val requested = if (params.has("protocolVersion")) params.get("protocolVersion").asString else "2025-11-25"
            return JsonObject().apply {
                addProperty("protocolVersion", if (requested in VERSIONS && requested != "2026-07-28") requested else "2025-11-25")
                add("capabilities", capabilities()); add("serverInfo", info())
                addProperty("instructions", "Coordinates are framebuffer pixels. Release held inputs when done. Inputs expire after 30 seconds without tool calls.")
            }
        }
        private fun discover(): JsonObject = JsonObject().apply {
            add("supportedVersions", versions()); add("capabilities", capabilities())
            addProperty("ttlMs", 0); addProperty("cacheScope", "private"); modern(this)
        }
        private fun modern(result: JsonObject) {
            result.addProperty("resultType", "complete")
            result.add("_meta", JsonObject().apply { add("io.modelcontextprotocol/serverInfo", info()) })
        }
        private fun capabilities(): JsonObject = JsonObject().apply { add("tools", JsonObject()) }
        private fun info(): JsonObject = JsonObject().apply { addProperty("name", "minecraft-mcp"); addProperty("version", "1.0.0") }
        @JvmStatic
        fun list(): JsonObject = JsonObject().apply { add("tools", ToolSchemas.list()); addProperty("ttlMs", 60000); addProperty("cacheScope", "private") }
        @JvmStatic
        fun text(value: Any?): JsonObject = JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "text"); addProperty("text", if (value is String) value else GSON.toJson(value)) })
            })
        }
        @JvmStatic
        fun failure(message: String): JsonObject = text(message).apply { addProperty("isError", true) }
        private fun error(id: JsonElement, code: Int, message: String?): JsonObject = JsonObject().apply {
            addProperty("jsonrpc", "2.0"); add("id", id)
            add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
        }
        private fun send(exchange: HttpExchange, status: Int, body: JsonObject?) {
            exchange.responseHeaders.set("Cache-Control", "no-store")
            if (body == null) { exchange.sendResponseHeaders(status, -1); return }
            val bytes = GSON.toJson(body).toByteArray(UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }
}
