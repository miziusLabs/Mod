package com.mizius.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ToolSchemas {
    @JvmField val NAMES: Set<String> = java.util.Set.of("join","screenshot","click","move","drag","scroll","key","type","execute_command","get_state","get_logs","batch")
    private const val NUMBER = """{"type":"number"}"""
    private const val STRING = """{"type":"string"}"""
    private const val POINT = """{"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"}},"required":["x","y"],"additionalProperties":false}"""
    private const val BUTTON = """{"oneOf":[{"type":"integer","minimum":0,"maximum":7},{"type":"string","enum":["left","right","middle"]}]}"""
    private const val ACTION = """{"type":"string","enum":["press","hold","release"]}"""
    @JvmStatic fun list(): JsonArray {
        val schemas = JsonArray()
        add(schemas,"join","Connect to a server, for example localhost:25565. Returns after beginning connection.","""{"ip":$STRING}""","ip")
        add(schemas,"screenshot","Capture the current framebuffer as MCP PNG image content.","{}")
        add(schemas,"get_state","Get client state, framebuffer and GUI dimensions, held inputs and player position. Renews held-input timeout.","{}")
        add(schemas,"get_logs","Read the latest matching lines from logs/latest.log. Queries are case-insensitive literal substrings; any query can match. Empty queries returns the tail. Scans at most the latest 16 MiB and reports truncation. Runs off the game thread and does not renew held inputs.","""{"queries":{"type":"array","maxItems":16,"items":{"type":"string","maxLength":256},"default":[]},"limit":{"type":"integer","minimum":1,"maximum":1000,"default":100}}""")
        add(schemas,"move","Move in framebuffer pixels. In gameplay changes look direction by the cursor displacement; in GUIs moves or drags the pointer.","""{"x":$NUMBER,"y":$NUMBER}""","x","y")
        add(schemas,"click","Click a mouse button at framebuffer coordinates. Optional action hold/release supports persistent buttons and multi-call dragging; default press.","""{"x":$NUMBER,"y":$NUMBER,"button":$BUTTON,"action":$ACTION}""","x","y","button")
        add(schemas,"drag","Drag a held button between framebuffer coordinates over multiple ticks.","""{"from":$POINT,"to":$POINT,"button":$BUTTON,"duration_ms":{"type":"integer","minimum":50,"maximum":10000,"default":250}}""","from","to","button")
        add(schemas,"scroll","Scroll vertically. Positive moves up; negative moves down.","""{"amount":$NUMBER}""","amount")
        add(schemas,"key","Press, hold or release a GLFW key name such as W, LEFT_SHIFT, ENTER, ESCAPE, or a numeric GLFW code. Holds can overlap; expire after 30 seconds without calls.","""{"key":{"oneOf":[{"type":"string"},{"type":"integer","minimum":32,"maximum":348}]},"action":$ACTION}""","key","action")
        add(schemas,"type","Type Unicode text into the open interface. Open chat with key T before typing chat text, then press ENTER to send.","""{"text":$STRING}""","text")
        add(schemas,"execute_command","Send a Minecraft command through the connection, with or without a leading slash. Server permissions apply.","""{"command":$STRING}""","command")
        add(schemas,"batch","Execute ordered actions without interleaving other calls. Each action may delay before running. Stops on first error; earlier actions stay applied. Max 128 actions, 8 screenshots, 20 seconds of delays/drags and 30 seconds of execution.","""{"actions":{"type":"array","minItems":1,"maxItems":128,"items":{"type":"object","properties":{"tool":{"type":"string","enum":["join","screenshot","click","move","drag","scroll","key","type","execute_command","get_state","get_logs"]},"arguments":{"type":"object"},"delay_ms":{"type":"integer","minimum":0,"maximum":10000}},"required":["tool"],"additionalProperties":false}}}""","actions")
        return schemas
    }
    private fun add(list: JsonArray, name: String, description: String, properties: String, vararg required: String) {
        val tool = JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonParser.parseString(properties))
                add("required", JsonArray().apply { required.forEach { add(it) } })
                addProperty("additionalProperties", false)
            })
        }
        list.add(tool)
    }
}
