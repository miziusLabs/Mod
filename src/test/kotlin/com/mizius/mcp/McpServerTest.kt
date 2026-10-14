package com.mizius.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class McpServerTest {
    private lateinit var server: McpServer
    private val calls = CopyOnWriteArrayList<String>()
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        server = McpServer({ name, _ ->
            calls.add(name)
            CompletableFuture.completedFuture(if (name == "type") McpServer.failure("no screen") else McpServer.text("ok"))
        }, 0, "")
        server.start()
    }

    @AfterEach
    fun stop() = server.close()

    private fun post(body: String, vararg headers: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port()}/mcp"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        headers.toList().chunked(2).forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun result(body: String): JsonObject =
        JsonParser.parseString(post(body).body()).asJsonObject.getAsJsonObject("result")

    @Test
    fun listsAllModTools() {
        val list = result("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").getAsJsonArray("tools")
        val names = list.map { it.asJsonObject.get("name").asString }.toSet()
        assertEquals(ToolSchemas.NAMES, names)
        assertFalse("start" in names)
    }

    @Test
    fun supportsLegacyHandshakeAndModernDiscovery() {
        assertEquals("2025-11-25", result("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-28"}}""").get("protocolVersion").asString)
        val discovered = result("""{"jsonrpc":"2.0","id":2,"method":"server/discover"}""")
        assertEquals("complete", discovered.get("resultType").asString)
        assertTrue(discovered.has("supportedVersions"))
        assertTrue(discovered.getAsJsonObject("_meta").has("io.modelcontextprotocol/serverInfo"))
    }

    @Test
    fun rejectsBrowserOriginMalformedAndOversizedMessages() {
        assertEquals(403, post("{}", "Origin", "https://example.com").statusCode())
        assertEquals(400, post("{").statusCode())
        assertEquals(400, post("[]").statusCode())
        assertEquals(413, post(" ".repeat(1_048_577)).statusCode())
        assertEquals(400, post("""{"jsonrpc":"2.0","id":true,"method":"ping"}""").statusCode())
    }

    @Test
    fun notificationsDoNotExecuteActions() {
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"tools/call","params":{"name":"key"}}""").statusCode())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun batchesAreOrderedAndStopAtFailure() {
        val response = result("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"batch","arguments":{"actions":[{"tool":"key"},{"tool":"type"},{"tool":"screenshot"}]}}}""")
        assertTrue(response.get("isError").asBoolean)
        assertEquals(listOf("key", "type"), calls)
    }

    @Test
    fun rejectsNestedBatchesBeforeExecution() {
        val response = result("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"batch","arguments":{"actions":[{"tool":"key"},{"tool":"batch"}]}}}""")
        assertTrue(response.get("isError").asBoolean)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun validatesModernRoutingAndAddsResultMetadata() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}"""
        assertEquals(400, post(body, "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "ping").statusCode())
        val response = post(body, "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list")
        assertEquals(200, response.statusCode())
        assertEquals("complete", JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("result").get("resultType").asString)
    }

    @Test
    fun enforcesOptionalBearerToken() {
        server.close()
        server = McpServer({ _, _ -> CompletableFuture.completedFuture(McpServer.text("ok")) }, 0, "test-secret")
        server.start()
        assertEquals(401, post("{}").statusCode())
        assertEquals(200, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "Authorization", "Bearer test-secret").statusCode())
    }
}
