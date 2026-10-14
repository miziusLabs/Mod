package com.mizius.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import com.mizius.mcp.mixin.KeyboardAccess
import com.mizius.mcp.mixin.MouseAccess
import net.minecraft.SharedConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import org.lwjgl.glfw.GLFW
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/** All mutable input state and game operations belong to the client thread. */
class ClientControl(private val mc: Minecraft) : McpServer.Tools {
    private val keys = ConcurrentHashMap.newKeySet<Int>()
    private val buttons = hashSetOf<Int>()
    private val pending = PriorityQueue(compareBy<Scheduled> { it.due })
    private val screenshots = ArrayDeque<CompletableFuture<JsonObject>>()
    @Volatile private var leaseUntil = 0L

    private data class Scheduled(val due: Long, val task: () -> Unit)

    fun active(): Boolean = System.nanoTime() < leaseUntil
    fun held(key: Int): Boolean = key in keys

    private fun renew() { leaseUntil = System.nanoTime() + LEASE_NANOS }
    private fun keyboard() = mc.keyboardHandler as KeyboardAccess
    private fun mouse() = mc.mouseHandler as MouseAccess
    private fun window(): Long = mc.getWindow().handle()

    fun tick() {
        if (!active() && (keys.isNotEmpty() || buttons.isNotEmpty())) releaseAll()
        while (pending.isNotEmpty() && pending.peek().due <= System.nanoTime()) pending.remove().task()
        // Focus loss and opening screens can reset Minecraft's key mappings.
        if (active() && mc.gui.screen() == null) {
            for (key in keys) KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), true)
            for (button in buttons) KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), true)
        }
    }

    private fun later(ms: Long, task: () -> Unit) {
        pending.add(Scheduled(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms), task))
    }

    private fun modifiers(): Int =
        (if (held(GLFW.GLFW_KEY_LEFT_SHIFT) || held(GLFW.GLFW_KEY_RIGHT_SHIFT)) GLFW.GLFW_MOD_SHIFT else 0) or
            (if (held(GLFW.GLFW_KEY_LEFT_CONTROL) || held(GLFW.GLFW_KEY_RIGHT_CONTROL)) GLFW.GLFW_MOD_CONTROL else 0) or
            (if (held(GLFW.GLFW_KEY_LEFT_ALT) || held(GLFW.GLFW_KEY_RIGHT_ALT)) GLFW.GLFW_MOD_ALT else 0) or
            (if (held(GLFW.GLFW_KEY_LEFT_SUPER) || held(GLFW.GLFW_KEY_RIGHT_SUPER)) GLFW.GLFW_MOD_SUPER else 0)

    private fun keyEvent(code: Int, down: Boolean) {
        if (down) keys.add(code) else keys.remove(code)
        keyboard().`mcp$keyPress`(window(), if (down) GLFW.GLFW_PRESS else GLFW.GLFW_RELEASE, KeyEvent(code, 0, modifiers()))
        if (!down) KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(code), false)
    }

    private fun buttonEvent(button: Int, down: Boolean) {
        if (down) buttons.add(button) else buttons.remove(button)
        mouse().`mcp$onButton`(window(), MouseButtonInfo(button, modifiers()), if (down) GLFW.GLFW_PRESS else GLFW.GLFW_RELEASE)
        if (!down) KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), false)
    }

    private fun releaseAll() {
        for (key in keys.toList()) keyEvent(key, false)
        for (button in buttons.toList()) buttonEvent(button, false)
    }

    private fun move(x: Double, y: Double) {
        // Tools use framebuffer pixels, GLFW callbacks use window logical pixels.
        mouse().`mcp$ignoreFirstMove`(false)
        mouse().`mcp$onMove`(window(), x * mc.getWindow().getScreenWidth() / mc.getWindow().getWidth(),
            y * mc.getWindow().getScreenHeight() / mc.getWindow().getHeight())
        mc.mouseHandler.handleAccumulatedMovement()
    }

    override fun call(name: String, arguments: JsonObject): CompletableFuture<JsonObject> {
        val args = arguments
        val result = CompletableFuture<JsonObject>()
        mc.execute {
            if (result.isDone) return@execute
            try {
                renew()
                when (name) {
                    "get_state" -> result.complete(McpServer.text(state()))
                    "join" -> {
                        val ip = string(args, "ip", 255)
                        require(!blank(ip)) { "ip cannot be empty" }
                        if (mc.level != null) mc.disconnectWithSavingScreen()
                        ConnectScreen.startConnecting(TitleScreen(), mc, ServerAddress.parseString(ip),
                            ServerData("MCP", ip, ServerData.Type.OTHER), false, null)
                        result.complete(ok())
                    }
                    "move" -> { move(number(args, "x"), number(args, "y")); result.complete(ok()) }
                    "scroll" -> { mouse().`mcp$onScroll`(window(), 0.0, number(args, "amount")); result.complete(ok()) }
                    "click" -> {
                        val button = button(args)
                        val action = if (args.has("action")) string(args, "action", 16) else "press"
                        require(action in INPUT_ACTIONS) { "Invalid action" }
                        require(action != "press" || button !in buttons) { "Button already held" }
                        move(number(args, "x"), number(args, "y"))
                        buttonEvent(button, action != "release")
                        if (action == "press") later(75) { finishRelease(result) { buttonEvent(button, false) } }
                        else result.complete(ok())
                    }
                    "key" -> {
                        val key = keyCode(args.get("key"))
                        val action = string(args, "action", 16)
                        require(action in INPUT_ACTIONS) { "Invalid action" }
                        require(action != "press" || key !in keys) { "Key already held" }
                        keyEvent(key, action != "release")
                        if (action == "press") later(75) { finishRelease(result) { keyEvent(key, false) } }
                        else result.complete(ok())
                    }
                    "type" -> {
                        check(mc.gui.screen() != null) { "Open a text interface first, for example with key T" }
                        string(args, "text", 32768).codePoints().forEach { cp ->
                            keyboard().`mcp$charTyped`(window(), CharacterEvent(cp))
                        }
                        result.complete(ok())
                    }
                    "execute_command" -> {
                        val connection = checkNotNull(mc.getConnection()) { "Not connected to a world" }
                        val command = string(args, "command", 32500).removePrefix("/")
                        require(!blank(command) && '\n' !in command && '\r' !in command) { "Invalid command" }
                        connection.sendCommand(command)
                        result.complete(ok())
                    }
                    "drag" -> drag(args, result)
                    "screenshot" -> screenshots.add(result)
                    else -> throw IllegalArgumentException("Unknown tool: $name")
                }
            } catch (e: Exception) { result.completeExceptionally(e) }
        }
        return result
    }

    private fun finishRelease(result: CompletableFuture<JsonObject>, release: () -> Unit) {
        try { release(); result.complete(ok()) }
        catch (e: Exception) { result.completeExceptionally(e) }
    }

    private fun drag(args: JsonObject, result: CompletableFuture<JsonObject>) {
        val from = args.getAsJsonObject("from")
        val to = args.getAsJsonObject("to")
        val x1 = number(from, "x")
        val y1 = number(from, "y")
        val x2 = number(to, "x")
        val y2 = number(to, "y")
        val button = button(args)
        val duration = if (args.has("duration_ms")) integer(args, "duration_ms", 50, 10000) else 250
        require(button !in buttons) { "Button already held" }
        move(x1, y1)
        buttonEvent(button, true)
        val steps = max(2, duration / 50)
        for (step in 1..steps) {
            later(duration.toLong() * step / steps) {
                try {
                    if (!result.isDone) move(x1 + (x2 - x1) * step / steps, y1 + (y2 - y1) * step / steps)
                    if (step == steps || result.isDone) {
                        buttonEvent(button, false)
                        result.complete(ok())
                    }
                } catch (e: Exception) {
                    buttonEvent(button, false)
                    result.completeExceptionally(e)
                }
            }
        }
    }

    private fun screenshot(result: CompletableFuture<JsonObject>) {
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget()) { image ->
            try {
                image.use {
                    ByteArrayOutputStream().use { output ->
                        val png = BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB)
                        for (y in 0 until image.getHeight()) for (x in 0 until image.getWidth()) png.setRGB(x, y, image.getPixel(x, y))
                        ImageIO.write(png, "png", output)
                        val content = JsonObject().apply {
                            addProperty("type", "image")
                            addProperty("mimeType", "image/png")
                            addProperty("data", Base64.getEncoder().encodeToString(output.toByteArray()))
                        }
                        result.complete(JsonObject().apply { add("content", JsonArray().apply { add(content) }) })
                    }
                }
            } catch (e: Exception) { result.completeExceptionally(e) }
        }
    }

    fun frame() {
        while (screenshots.isNotEmpty()) {
            val result = screenshots.remove()
            if (!result.isDone) {
                try { screenshot(result) } catch (e: Exception) { result.completeExceptionally(e) }
            }
        }
    }

    private fun state(): JsonObject = JsonObject().apply {
        addProperty("version", SharedConstants.getCurrentVersion().name())
        addProperty("width", mc.getWindow().getWidth())
        addProperty("height", mc.getWindow().getHeight())
        addProperty("gui_width", mc.getWindow().getGuiScaledWidth())
        addProperty("gui_height", mc.getWindow().getGuiScaledHeight())
        addProperty("window_width", mc.getWindow().getScreenWidth())
        addProperty("window_height", mc.getWindow().getScreenHeight())
        addProperty("mouse_x", mc.mouseHandler.xpos() * mc.getWindow().getWidth() / mc.getWindow().getScreenWidth())
        addProperty("mouse_y", mc.mouseHandler.ypos() * mc.getWindow().getHeight() / mc.getWindow().getScreenHeight())
        addProperty("screen", mc.gui.screen()?.javaClass?.simpleName ?: "gameplay")
        addProperty("connected", mc.getConnection() != null)
        addProperty("in_world", mc.level != null)
        addProperty("paused", mc.isPaused())
        addProperty("window_focused", mc.getWindow().isFocused())
        addProperty("mouse_grabbed", mc.mouseHandler.isMouseGrabbed())
        addProperty("held_input_timeout_ms", 30000)
        add("held_keys", JsonArray().apply { keys.sorted().forEach { add(it) } })
        add("held_buttons", JsonArray().apply { buttons.sorted().forEach { add(it) } })
        mc.getCurrentServer()?.let { addProperty("server", it.ip) }
        mc.player?.let {
            addProperty("x", it.getX())
            addProperty("y", it.getY())
            addProperty("z", it.getZ())
            addProperty("yaw", it.getYRot())
            addProperty("pitch", it.getXRot())
        }
    }

    companion object {
        private val LEASE_NANOS = TimeUnit.SECONDS.toNanos(30)
        private val INPUT_ACTIONS = setOf("press", "hold", "release")
        private fun ok(): JsonObject = McpServer.text("ok")
        private fun blank(value: String): Boolean = value.codePoints().allMatch(Character::isWhitespace)

        @JvmStatic
        fun string(args: JsonObject, field: String, max: Int): String {
            val value = args.get(field)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$field must be a string" }
            val text = value.asString
            require(text.length <= max) { "$field is too long" }
            return text
        }

        @JvmStatic
        fun number(args: JsonObject?, field: String): Double {
            require(args != null) { "Missing coordinates" }
            val value = args.get(field)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$field must be a number" }
            val number = value.asDouble
            require(number.isFinite() && abs(number) <= 1_000_000) { "$field out of range" }
            return number
        }

        @JvmStatic
        fun integer(args: JsonObject, field: String, min: Int, max: Int): Int {
            val number = number(args, field)
            require(number == Math.rint(number) && number >= min && number <= max) { "$field out of range" }
            return number.toInt()
        }

        @JvmStatic
        fun button(args: JsonObject): Int {
            val button = args.get("button")
            if (button != null && button.isJsonPrimitive && button.asJsonPrimitive.isString) {
                return when (button.asString.lowercase(Locale.ROOT)) {
                    "left" -> 0
                    "right" -> 1
                    "middle" -> 2
                    else -> throw IllegalArgumentException("Unknown button")
                }
            }
            return integer(args, "button", 0, 7)
        }

        @JvmStatic
        fun keyCode(key: JsonElement?): Int {
            require(key != null && key.isJsonPrimitive) { "Missing key" }
            if (key.asJsonPrimitive.isNumber) {
                val number = key.asInt
                require(number in 32..348 && number.toDouble() == key.asDouble) { "Invalid GLFW key code" }
                return number
            }
            val name = when (val name = key.asString.uppercase(Locale.ROOT).replace(' ', '_')) {
                "CTRL", "CONTROL" -> "LEFT_CONTROL"
                "SHIFT" -> "LEFT_SHIFT"
                "ALT" -> "LEFT_ALT"
                "ESC" -> "ESCAPE"
                "RETURN" -> "ENTER"
                else -> name
            }
            try { return GLFW::class.java.getField("GLFW_KEY_$name").getInt(null) }
            catch (e: ReflectiveOperationException) { throw IllegalArgumentException("Unknown key: $name") }
        }
    }
}
