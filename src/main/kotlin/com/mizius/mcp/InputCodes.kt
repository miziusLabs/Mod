package com.mizius.mcp

import com.google.gson.JsonElement
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.KeyEvent
import java.util.Locale

/**
 * Keeps the MCP input protocol on GLFW codes while adapting callbacks to the
 * input constants used by the Minecraft version that loaded this JAR.
 */
object InputCodes {
    private val backend: InputCodeBackend by lazy(LazyThreadSafetyMode.PUBLICATION) {
        InputCodeBackend(
            constant = ::inputConstant,
            keyName = PROTOCOL_KEY_NAMES_BY_CODE::get,
            protocolKey = PROTOCOL_KEY_CODES_BY_NAME::get,
            sdl = KeyEvent::class.java.recordComponents?.any { it.name == "keycode" } == true
        )
    }

    /** Parse a key name or numeric GLFW key code without translating its protocol value. */
    @JvmStatic
    fun keyCode(key: JsonElement?): Int = parseKeyCode(key, PROTOCOL_KEY_CODES_BY_NAME::get)

    /** Return the runtime Minecraft key for a protocol GLFW key code. */
    @JvmStatic
    fun key(code: Int): InputConstants.Key = backend.key(code)

    /** Translate a protocol GLFW key code to the runtime Minecraft keyboard code. */
    @JvmStatic
    fun nativeKey(code: Int): Int = backend.nativeKey(code)

    /** Translate the protocol's zero-based mouse button to the runtime input code. */
    @JvmStatic
    fun mouseButton(protocolButton: Int): Int = backend.mouseButton(protocolButton)

    /** Create an event with the version-appropriate second argument. */
    @JvmStatic
    fun keyEvent(code: Int, modifiers: Int): KeyEvent = backend.keyEvent(code, modifiers)

    /** Build runtime modifier bits from held protocol GLFW key codes. */
    @JvmStatic
    fun modifiers(held: (Int) -> Boolean): Int = backend.modifiers(held)

    /** Runtime action values; resolved reflectively because fields are inlined by javac. */
    val press: Int get() = backend.press
    val release: Int get() = backend.release

    internal fun parseKeyCode(key: JsonElement?, lookup: (String) -> Int?): Int {
        require(key != null && key.isJsonPrimitive) { "Missing key" }
        val primitive = key.asJsonPrimitive
        if (primitive.isNumber) {
            val number = primitive.asDouble
            require(number.isFinite() && number in 32.0..348.0 && number == Math.rint(number)) {
                "Invalid GLFW key code"
            }
            return number.toInt()
        }
        require(primitive.isString) { "Key must be a GLFW name or number" }
        val name = when (val input = primitive.asString.uppercase(Locale.ROOT).replace(' ', '_')) {
            "CTRL", "CONTROL" -> "LEFT_CONTROL"
            "SHIFT" -> "LEFT_SHIFT"
            "ALT" -> "LEFT_ALT"
            "ESC" -> "ESCAPE"
            "RETURN" -> "ENTER"
            else -> input
        }
        return lookup(name) ?: throw IllegalArgumentException("Unknown key: $name")
    }

    // These are protocol GLFW key values. Keep them fixed so a 26.3 runtime
    // does not need the GLFW class that its SDL input backend removed.
    private val PROTOCOL_KEY_CODES_BY_NAME: Map<String, Int> = buildMap {
        put("SPACE", 32)
        put("APOSTROPHE", 39)
        put("COMMA", 44)
        put("MINUS", 45)
        put("PERIOD", 46)
        put("SLASH", 47)
        for (digit in 0..9) put(digit.toString(), 48 + digit)
        put("SEMICOLON", 59)
        put("EQUAL", 61)
        for (letter in 'A'..'Z') put(letter.toString(), letter.code)
        put("LEFT_BRACKET", 91)
        put("BACKSLASH", 92)
        put("RIGHT_BRACKET", 93)
        put("GRAVE_ACCENT", 96)
        put("WORLD_1", 161)
        put("WORLD_2", 162)
        put("ESCAPE", 256)
        put("ENTER", 257)
        put("TAB", 258)
        put("BACKSPACE", 259)
        put("INSERT", 260)
        put("DELETE", 261)
        put("RIGHT", 262)
        put("LEFT", 263)
        put("DOWN", 264)
        put("UP", 265)
        put("PAGE_UP", 266)
        put("PAGE_DOWN", 267)
        put("HOME", 268)
        put("END", 269)
        put("CAPS_LOCK", 280)
        put("SCROLL_LOCK", 281)
        put("NUM_LOCK", 282)
        put("PRINT_SCREEN", 283)
        put("PAUSE", 284)
        for (function in 1..25) put("F$function", 289 + function)
        for (digit in 0..9) put("KP_$digit", 320 + digit)
        put("KP_DECIMAL", 330)
        put("KP_DIVIDE", 331)
        put("KP_MULTIPLY", 332)
        put("KP_SUBTRACT", 333)
        put("KP_ADD", 334)
        put("KP_ENTER", 335)
        put("KP_EQUAL", 336)
        put("LEFT_SHIFT", 340)
        put("LEFT_CONTROL", 341)
        put("LEFT_ALT", 342)
        put("LEFT_SUPER", 343)
        put("RIGHT_SHIFT", 344)
        put("RIGHT_CONTROL", 345)
        put("RIGHT_ALT", 346)
        put("RIGHT_SUPER", 347)
        put("MENU", 348)
    }
    private val PROTOCOL_KEY_NAMES_BY_CODE: Map<Int, String> =
        PROTOCOL_KEY_CODES_BY_NAME.entries.associate { (name, code) -> code to name }

    private fun inputConstant(name: String): Int? {
        try { return InputConstants::class.java.getField(name).getInt(null) }
        catch (_: ReflectiveOperationException) { }

        val keyName = when (name) {
            "KEY_MENU" -> "key.keyboard.menu"
            "KEY_WORLD_2" -> "key.keyboard.world.2"
            "KEY_KP_DIVIDE" -> "key.keyboard.keypad.divide"
            "KEY_KP_SUBTRACT" -> "key.keyboard.keypad.subtract"
            else -> return null
        }
        return try { InputConstants.getKey(keyName).value }
        catch (_: RuntimeException) { null }
    }
}

/** Runtime adapter is separated from Minecraft constants so both backends can be tested with maps. */
internal class InputCodeBackend(
    private val constant: (String) -> Int?,
    private val keyName: (Int) -> String?,
    private val protocolKey: (String) -> Int?,
    private val sdl: Boolean
) {
    val press: Int get() = requiredConstant("PRESS")
    val release: Int get() = requiredConstant("RELEASE")

    fun key(code: Int): InputConstants.Key {
        val backendName = if (InputConstants.Type.values().any { it.name == "KEYBOARD" }) "KEYBOARD" else "KEYSYM"
        return InputConstants.Type.valueOf(backendName).getOrCreate(nativeKey(code))
    }

    fun nativeKey(code: Int): Int {
        // 26.2 uses GLFW key values directly, including valid numeric codes
        // without named InputConstants fields.
        if (!sdl) return code
        val glfwName = keyName(code) ?: throw unsupportedKey("GLFW code $code")
        val candidates = when (glfwName) {
            "LEFT_SHIFT" -> listOf("KEY_LSHIFT")
            "RIGHT_SHIFT" -> listOf("KEY_RSHIFT")
            "LEFT_CONTROL" -> listOf("KEY_LCONTROL")
            "RIGHT_CONTROL" -> listOf("KEY_RCONTROL")
            "LEFT_ALT" -> listOf("KEY_LALT")
            "RIGHT_ALT" -> listOf("KEY_RALT")
            "LEFT_SUPER" -> listOf("KEY_LGUI", "KEY_LSUPER")
            "RIGHT_SUPER" -> listOf("KEY_RGUI", "KEY_RSUPER")
            "ENTER" -> listOf("KEY_RETURN")
            "PAGE_UP" -> listOf("KEY_PAGEUP")
            "PAGE_DOWN" -> listOf("KEY_PAGEDOWN")
            "LEFT_BRACKET" -> listOf("KEY_LBRACKET")
            "RIGHT_BRACKET" -> listOf("KEY_RBRACKET")
            "EQUAL" -> listOf("KEY_EQUALS")
            "GRAVE_ACCENT" -> listOf("KEY_GRAVE")
            "CAPS_LOCK" -> listOf("KEY_CAPSLOCK")
            "SCROLL_LOCK" -> listOf("KEY_SCROLLLOCK")
            "NUM_LOCK" -> listOf("KEY_NUMLOCK")
            "KP_0" -> listOf("KEY_NUMPAD0")
            "KP_1" -> listOf("KEY_NUMPAD1")
            "KP_2" -> listOf("KEY_NUMPAD2")
            "KP_3" -> listOf("KEY_NUMPAD3")
            "KP_4" -> listOf("KEY_NUMPAD4")
            "KP_5" -> listOf("KEY_NUMPAD5")
            "KP_6" -> listOf("KEY_NUMPAD6")
            "KP_7" -> listOf("KEY_NUMPAD7")
            "KP_8" -> listOf("KEY_NUMPAD8")
            "KP_9" -> listOf("KEY_NUMPAD9")
            "KP_DECIMAL" -> listOf("KEY_NUMPADCOMMA")
            "KP_DIVIDE" -> listOf("KEY_KP_DIVIDE")
            "KP_ENTER" -> listOf("KEY_NUMPADENTER")
            "KP_EQUAL" -> listOf("KEY_NUMPADEQUALS")
            "KP_SUBTRACT" -> listOf("KEY_KP_SUBTRACT")
            "KP_ADD" -> listOf("KEY_ADD")
            "KP_MULTIPLY" -> listOf("KEY_MULTIPLY")
            "PRINT_SCREEN" -> listOf("KEY_PRINTSCREEN")
            "MENU" -> listOf("KEY_MENU")
            "WORLD_2" -> listOf("KEY_WORLD_2")
            // SDL has no matching scancode for GLFW WORLD_1 or F25.
            "WORLD_1", "F25" -> emptyList()
            else -> listOf("KEY_$glfwName")
        }
        return candidates.firstNotNullOfOrNull(constant) ?: throw unsupportedKey(glfwName)
    }

    fun mouseButton(protocolButton: Int): Int {
        require(protocolButton in 0..7) { "Invalid mouse button: $protocolButton" }
        val suffix = when (protocolButton) {
            0 -> "LEFT"
            1 -> "RIGHT"
            2 -> "MIDDLE"
            else -> (protocolButton + 1).toString()
        }
        return requiredConstant("MOUSE_BUTTON_$suffix")
    }

    fun keyEvent(code: Int, modifiers: Int): KeyEvent =
        KeyEvent(nativeKey(code), if (sdl) sdlKeycode(code) else 0, modifiers)

    fun modifiers(held: (Int) -> Boolean): Int {
        var result = 0
        if (isHeld(held, "LEFT_SHIFT", "RIGHT_SHIFT")) result = result or requiredConstant("MOD_SHIFT")
        if (isHeld(held, "LEFT_CONTROL", "RIGHT_CONTROL")) result = result or requiredConstant("MOD_CONTROL")
        if (isHeld(held, "LEFT_ALT", "RIGHT_ALT")) result = result or requiredConstant("MOD_ALT")
        if (isHeld(held, "LEFT_SUPER", "RIGHT_SUPER")) result = result or requiredConstant("MOD_SUPER")
        return result
    }

    private fun isHeld(held: (Int) -> Boolean, vararg names: String): Boolean =
        names.any { name -> protocolKey(name)?.let(held) == true }

    private fun sdlKeycode(code: Int): Int {
        val name = keyName(code) ?: throw unsupportedKey("GLFW code $code")
        val keycodeName = when (name) {
            "ENTER" -> "RETURN"
            "PAGE_UP" -> "PAGEUP"
            "PAGE_DOWN" -> "PAGEDOWN"
            "KP_ENTER" -> "NUMPADENTER"
            "LEFT_CONTROL" -> "LCONTROL"
            "RIGHT_CONTROL" -> "RCONTROL"
            else -> name
        }
        constant("KEYCODE_$keycodeName")?.let { return it }
        if (name.length == 1 && name[0] in 'A'..'Z') return name[0].lowercaseChar().code
        if (name.length == 1 && name[0] in '0'..'9') return name[0].code
        val printable = when (name) {
            "SPACE" -> ' '.code
            "APOSTROPHE" -> '\''.code
            "COMMA" -> ','.code
            "MINUS" -> '-'.code
            "PERIOD" -> '.'.code
            "SLASH" -> '/'.code
            "SEMICOLON" -> ';'.code
            "EQUAL" -> '='.code
            "LEFT_BRACKET" -> '['.code
            "BACKSLASH" -> '\\'.code
            "RIGHT_BRACKET" -> ']'.code
            "GRAVE_ACCENT" -> '`'.code
            "TAB" -> '\t'.code
            "ENTER" -> '\r'.code
            "BACKSPACE" -> '\b'.code
            "ESCAPE" -> 27
            "DELETE" -> 127
            else -> null
        }
        return printable ?: (SDL_SCANCODE_MASK or nativeKey(code))
    }

    private fun requiredConstant(name: String): Int = constant(name)
        ?: throw IllegalStateException("Minecraft input constant $name is unavailable")

    private fun unsupportedKey(name: String) = IllegalArgumentException(
        "Key $name is not supported by the current Minecraft input backend"
    )

    private companion object {
        const val SDL_SCANCODE_MASK = 1 shl 30
    }
}
