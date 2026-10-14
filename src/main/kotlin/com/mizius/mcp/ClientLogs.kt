package com.mizius.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale

/** Reads only the configured client's current log, with bounded memory and IO. */
class ClientLogs(private val file: Path) {
    @Throws(IOException::class)
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

        val matches = ArrayDeque<String>()
        var limited = false
        var scanned: Int
        val size: Long
        RandomAccessFile(file.toFile(), "r").use { log ->
            size = log.length()
            scanned = minOf(size, MAX_SCAN_BYTES.toLong()).toInt()
            val offset = size - scanned
            log.seek(offset)
            val tail = ByteArray(scanned)
            // Handle rotation or truncation while the client writes to its log.
            var count = 0
            while (count < tail.size) {
                val read = log.read(tail, count, tail.size - count)
                if (read <= 0) break
                count += read
            }
            scanned = count
            String(tail, 0, count, StandardCharsets.UTF_8).reader().buffered().use { lines ->
                if (offset > 0) lines.readLine()
                lines.lineSequence().forEach { line ->
                    val lower = line.lowercase(Locale.ROOT)
                    if (queries.isEmpty() || queries.any(lower::contains)) {
                        if (matches.size == limit) {
                            matches.removeFirst()
                            limited = true
                        }
                        matches.addLast(line)
                    }
                }
            }
        }
        return JsonObject().apply {
            add("lines", JsonArray().apply { matches.forEach { add(it) } })
            addProperty("returned", matches.size)
            addProperty("limit", limit)
            addProperty("truncated", size > MAX_SCAN_BYTES || limited)
            addProperty("scanned_bytes", scanned)
            addProperty("file_bytes", size)
            addProperty("file", "logs/latest.log")
        }
    }

    companion object {
        const val MAX_SCAN_BYTES = 16 * 1024 * 1024
    }
}
