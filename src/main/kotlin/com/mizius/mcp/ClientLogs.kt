package com.mizius.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import java.beans.PropertyChangeListener
import java.util.Locale
import java.util.UUID

/** Captures the running client's logging events without depending on a launcher's log files. */
class ClientLogs(private val context: LoggerContext = LoggerContext.getContext(false)) : AutoCloseable {
    private data class Line(val text: String, val bytes: Int)

    private val lock = Any()
    private val lines = ArrayDeque<Line>()
    private var bufferedBytes = 0
    private var discarded = false
    private var closed = false
    private var attached = emptyList<LoggerConfig>()
    private val eventLayout = PatternLayout.newBuilder()
        .setPattern("[%d{HH:mm:ss}] [%t/%level] [%logger]: %msg%n%throwable")
        .build()
    private val appender = object : AbstractAppender("minecraft-mcp-${UUID.randomUUID()}", null, eventLayout, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            val formatted = eventLayout.toSerializable(event)
            synchronized(lock) {
                if (closed) return
                formatted.reader().buffered().use { reader ->
                    reader.lineSequence().forEach { text ->
                        val bytes = text.toByteArray(Charsets.UTF_8).size + 1
                        if (bytes > MAX_BUFFER_BYTES) {
                            discarded = true
                        } else {
                            while (lines.isNotEmpty() && (bufferedBytes + bytes > MAX_BUFFER_BYTES || lines.size >= MAX_BUFFER_LINES)) {
                                bufferedBytes -= lines.removeFirst().bytes
                                discarded = true
                            }
                            lines.addLast(Line(text, bytes))
                            bufferedBytes += bytes
                        }
                    }
                }
            }
        }
    }
    private val configurationListener = PropertyChangeListener { event ->
        if (event.propertyName == LoggerContext.PROPERTY_CONFIG) attach()
    }

    init {
        appender.start()
        context.addPropertyChangeListener(configurationListener)
        attach()
    }

    private fun attach() = synchronized(lock) {
        if (!closed) {
            attached.forEach { it.removeAppender(appender.name) }
            val configuration = context.configuration
            // Non-additive loggers do not forward events to the root logger.
            attached = (listOf(configuration.rootLogger) + configuration.loggers.values.filter { !it.isAdditive }).distinct()
            attached.forEach { it.addAppender(appender, null, null) }
        }
    }

    override fun close() {
        context.removePropertyChangeListener(configurationListener)
        synchronized(lock) {
            closed = true
            attached.forEach { it.removeAppender(appender.name) }
            attached = emptyList()
            appender.stop()
        }
    }

    fun read(arguments: JsonObject): JsonObject {
        val limit = if (arguments.has("limit")) ClientControl.integer(arguments, "limit", 1, 1000) else 100
        val queries = if (arguments.has("queries")) {
            val values = arguments.get("queries")
            require(values.isJsonArray) { "queries must be an array of strings" }
            require(values.asJsonArray.size() <= 16) { "At most 16 queries are allowed" }
            values.asJsonArray.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 256) {
                    "Each query must be a string of at most 256 characters"
                }
                value.asString.lowercase(Locale.ROOT)
            }
        } else emptyList()

        val snapshot: List<Line>
        val bytes: Int
        val truncated: Boolean
        synchronized(lock) {
            snapshot = lines.toList()
            bytes = bufferedBytes
            truncated = discarded
        }
        val matches = ArrayDeque<String>()
        var limited = false
        snapshot.forEach { line ->
            val lower = line.text.lowercase(Locale.ROOT)
            if (queries.isEmpty() || queries.any(lower::contains)) {
                if (matches.size == limit) {
                    matches.removeFirst()
                    limited = true
                }
                matches.addLast(line.text)
            }
        }
        return JsonObject().apply {
            add("lines", JsonArray().apply { matches.forEach { add(it) } })
            addProperty("returned", matches.size)
            addProperty("limit", limit)
            addProperty("truncated", truncated || limited)
            addProperty("buffered_bytes", bytes)
            addProperty("source", "client_logger")
        }
    }

    companion object {
        const val MAX_BUFFER_BYTES = 16 * 1024 * 1024
        const val MAX_BUFFER_LINES = 65536
    }
}
