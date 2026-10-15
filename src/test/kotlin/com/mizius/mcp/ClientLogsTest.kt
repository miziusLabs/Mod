package com.mizius.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.DefaultConfiguration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClientLogsTest {
    private lateinit var context: LoggerContext
    private lateinit var logs: ClientLogs

    private fun configuration() = DefaultConfiguration().apply {
        rootLogger.level = Level.ALL
        rootLogger.appenders.keys.toList().forEach(rootLogger::removeAppender)
    }

    @BeforeEach
    fun setup() {
        context = LoggerContext("client-logs-test")
        context.start(configuration())
        logs = ClientLogs(context)
    }

    @AfterEach
    fun close() {
        logs.close()
        context.close()
    }

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun lines(result: JsonObject): List<String> = result.getAsJsonArray("lines").map { it.asString }
    private fun messages(result: JsonObject): List<String> = lines(result).map { it.substringAfter("]: ") }
    private fun emit(text: String) = context.getLogger("client").info(text)

    @Test
    fun capturesClientEventsAndReturnsLatestMatchesInOrder() {
        emit("first")
        emit("second")
        emit("third")
        val result = logs.read(args("""{"limit":2}"""))
        assertEquals(listOf("second", "third"), messages(result))
        assertTrue(result.get("truncated").asBoolean)
        assertEquals(2, result.get("returned").asInt)
        assertEquals("client_logger", result.get("source").asString)
        assertFalse(result.has("file"))
    }

    @Test
    fun queriesUseCaseInsensitiveLiteralOrMatching() {
        emit("[INFO] start")
        emit("[WARN] Äpfel 🍎")
        emit("[ERROR] failure")
        emit("plain error text")
        val result = logs.read(args("""{"queries":["[warn]","[error]"],"limit":100}"""))
        assertEquals(listOf("[WARN] Äpfel 🍎", "[ERROR] failure"), messages(result))
        assertFalse(result.get("truncated").asBoolean)
        assertEquals(100, logs.read(JsonObject()).get("limit").asInt)
    }

    @Test
    fun emptyCaptureNeedsNoLogFile() {
        val result = logs.read(JsonObject())
        assertTrue(lines(result).isEmpty())
        assertFalse(result.get("truncated").asBoolean)
        assertEquals(0, result.get("buffered_bytes").asInt)
    }

    @Test
    fun rejectsInvalidLimitsAndQueries() {
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
    fun bufferEvictsOldLinesAtByteAndLineLimits() {
        emit("old match")
        val large = "Ä".repeat(512 * 1024)
        repeat(17) { emit(large) }
        emit("new match")
        val result = logs.read(args("""{"queries":["match"]}"""))
        assertEquals(listOf("new match"), messages(result))
        assertTrue(result.get("truncated").asBoolean)
        assertTrue(result.get("buffered_bytes").asInt <= ClientLogs.MAX_BUFFER_BYTES)
        repeat(ClientLogs.MAX_BUFFER_LINES) { emit("small") }
        assertTrue(lines(logs.read(args("""{"queries":["match"]}"""))).isEmpty())
    }

    @Test
    fun oversizedLinesAreSkippedWithoutDroppingOtherMessages() {
        emit("before")
        emit("x".repeat(ClientLogs.MAX_BUFFER_BYTES))
        emit("after")
        val result = logs.read(JsonObject())
        assertEquals(listOf("before", "after"), messages(result))
        assertTrue(result.get("truncated").asBoolean)
    }

    @Test
    fun capturesStackTracesAndMultilineMessages() {
        context.getLogger("client").error("failure\nmore detail", IllegalStateException("exception detail"))
        val result = lines(logs.read(JsonObject()))
        assertTrue(result.first().endsWith("failure"))
        assertEquals("more detail", result[1])
        assertTrue(result.any { it.contains("java.lang.IllegalStateException: exception detail") })
        assertTrue(result.any { it.contains("ClientLogsTest.capturesStackTracesAndMultilineMessages") })
    }

    @Test
    fun followsLoggerReconfigurationAndNonAdditiveLoggersWithoutDuplicates() {
        emit("before")
        val replacement = configuration()
        replacement.addLogger("isolated", LoggerConfig("isolated", Level.ALL, false))
        context.setConfiguration(replacement)
        emit("after")
        context.getLogger("isolated.child").warn("isolated event")
        assertEquals(listOf("before", "after", "isolated event"), messages(logs.read(JsonObject())))
        logs.close()
        emit("after close")
        assertEquals(3, lines(logs.read(JsonObject())).size)
    }

    @Test
    fun logsRemainAvailableWhileGameActionIsBusy() {
        emit("[WARN] client message")
        val pending = CompletableFuture<JsonObject>()
        val entered = CountDownLatch(1)
        McpServer({ _, _ -> entered.countDown(); pending }, 0, "", logs).use { server ->
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
            assertEquals(listOf("[WARN] client message"), messages(JsonParser.parseString(text).asJsonObject))
            assertFalse(busy.isDone)
            pending.complete(McpServer.text("ok"))
            assertEquals(200, busy.get(2, TimeUnit.SECONDS).statusCode())
        }
    }
}
