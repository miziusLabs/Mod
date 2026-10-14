package com.mizius.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClientLogsTest {
    @TempDir
    lateinit var directory: Path
    private lateinit var file: Path
    private lateinit var logs: ClientLogs

    @BeforeEach
    fun setup() {
        file = directory.resolve("latest.log")
        logs = ClientLogs(file)
    }

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun lines(result: JsonObject): List<String> = result.getAsJsonArray("lines").map { it.asString }

    @Test
    fun returnsLatestLinesInChronologicalOrder() {
        Files.writeString(file, "first\nsecond\nthird\n")
        val result = logs.read(args("""{"limit":2}"""))
        assertEquals(listOf("second", "third"), lines(result))
        assertTrue(result.get("truncated").asBoolean)
        assertEquals(2, result.get("returned").asInt)
    }

    @Test
    fun queriesUseCaseInsensitiveLiteralOrMatching() {
        Files.writeString(file, "[INFO] start\n[WARN] Äpfel 🍎\n[ERROR] failure\nplain error text\n")
        val result = logs.read(args("""{"queries":["[warn]","[error]"],"limit":100}"""))
        assertEquals(listOf("[WARN] Äpfel 🍎", "[ERROR] failure"), lines(result))
        assertFalse(result.get("truncated").asBoolean)
        assertEquals(100, logs.read(JsonObject()).get("limit").asInt)
    }

    @Test
    fun handlesEmptyAndMissingLogs() {
        assertThrows(FileNotFoundException::class.java) { logs.read(JsonObject()) }
        Files.writeString(file, "")
        val result = logs.read(JsonObject())
        assertTrue(lines(result).isEmpty())
        assertFalse(result.get("truncated").asBoolean)
    }

    @Test
    fun rejectsInvalidLimitsAndQueries() {
        Files.writeString(file, "line\n")
        listOf(
            """{"limit":0}""", """{"limit":1001}""", """{"limit":2.5}""",
            """{"limit":"2"}""", """{"queries":"error"}""", """{"queries":[3]}"""
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { logs.read(args(invalid)) }
        }
        val oversized = JsonObject().apply {
            add("queries", JsonArray().apply { repeat(17) { add("x") } })
        }
        assertThrows(IllegalArgumentException::class.java) { logs.read(oversized) }
        assertThrows(IllegalArgumentException::class.java) {
            logs.read(args("""{"queries":["${"x".repeat(257)}"]}"""))
        }
    }

    @Test
    fun scanIsBoundedAndSkipsPartialBoundaryLines() {
        RandomAccessFile(file.toFile(), "rw").use { log ->
            log.writeBytes("[ERROR] old match\n")
            log.setLength(ClientLogs.MAX_SCAN_BYTES + 100L)
            log.seek(log.length() - 20)
            log.writeBytes("\n[ERROR] new match\n")
        }
        val result = logs.read(args("""{"queries":["[error]"]}"""))
        assertEquals(listOf("[ERROR] new match"), lines(result))
        assertTrue(result.get("truncated").asBoolean)
        assertEquals(ClientLogs.MAX_SCAN_BYTES, result.get("scanned_bytes").asInt)
    }

    @Test
    fun followsCurrentLogAfterRotation() {
        Files.writeString(file, "old\n")
        assertEquals(listOf("old"), lines(logs.read(JsonObject())))
        Files.move(file, directory.resolve("previous.log"))
        Files.writeString(file, "new\n")
        assertEquals(listOf("new"), lines(logs.read(JsonObject())))
    }

    @Test
    fun logsRemainAvailableWhileGameActionIsBusy() {
        Files.writeString(file, "[WARN] client message\n")
        val pending = CompletableFuture<JsonObject>()
        val entered = CountDownLatch(1)
        McpServer({ _, _ -> entered.countDown(); pending }, 0, "", file).use { server ->
            server.start()
            val http = HttpClient.newHttpClient()
            val endpoint = URI.create("http://127.0.0.1:${server.port()}/mcp")
            fun request(body: String): HttpRequest = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val action = request("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"key"}}""")
            val busy = http.sendAsync(action, HttpResponse.BodyHandlers.ofString())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val read = request("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_logs","arguments":{"queries":["warn"],"limit":10}}}""")
            val response = http.send(read, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            val text = JsonParser.parseString(response.body()).asJsonObject
                .getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
            assertEquals(listOf("[WARN] client message"), lines(JsonParser.parseString(text).asJsonObject))
            assertFalse(busy.isDone)
            pending.complete(McpServer.text("ok"))
            assertEquals(200, busy.get(2, TimeUnit.SECONDS).statusCode())
        }
    }
}
