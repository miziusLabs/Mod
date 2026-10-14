package com.mizius.mcp

import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InputCodesTest {
    @Test
    fun `key names and aliases preserve GLFW protocol values`() {
        assertEquals(87, InputCodes.keyCode(JsonPrimitive("w")))
        assertEquals(341, InputCodes.keyCode(JsonPrimitive("ctrl")))
        assertEquals(344, InputCodes.keyCode(JsonPrimitive("right shift")))
        assertEquals(256, InputCodes.keyCode(JsonPrimitive("ESC")))
        assertEquals(257, InputCodes.keyCode(JsonPrimitive("return")))
        assertEquals(87, InputCodes.keyCode(JsonPrimitive(87)))
        assertEquals(314, InputCodes.keyCode(JsonPrimitive("F25")))
        assertEquals(348, InputCodes.keyCode(JsonPrimitive("menu")))
        assertEquals(162, InputCodes.keyCode(JsonPrimitive("world_2")))
    }

    @Test
    fun `key parser rejects malformed and out of range input`() {
        assertThrows(IllegalArgumentException::class.java) { InputCodes.keyCode(null) }
        assertThrows(IllegalArgumentException::class.java) { InputCodes.keyCode(JsonNull.INSTANCE) }
        assertThrows(IllegalArgumentException::class.java) { InputCodes.keyCode(JsonPrimitive(true)) }
        assertThrows(IllegalArgumentException::class.java) { InputCodes.keyCode(JsonPrimitive(87.5)) }
        assertThrows(IllegalArgumentException::class.java) { InputCodes.keyCode(JsonPrimitive(31)) }
        assertThrows(IllegalArgumentException::class.java) { InputCodes.keyCode(JsonPrimitive(349)) }
        assertThrows(IllegalArgumentException::class.java) { InputCodes.keyCode(JsonPrimitive("not_a_key")) }
    }

    @Test
    fun `runtime keyboard codes translate for both input backends`() {
        val glfw = backend(sdl = false)
        val sdl = backend(sdl = true)

        assertEquals(87, glfw.nativeKey(87))
        assertEquals(257, glfw.nativeKey(257))
        assertEquals(340, glfw.nativeKey(340))
        assertEquals(61, glfw.nativeKey(61))
        assertEquals(96, glfw.nativeKey(96))
        assertEquals(280, glfw.nativeKey(280))
        assertEquals(281, glfw.nativeKey(281))
        assertEquals(282, glfw.nativeKey(282))
        assertEquals(314, glfw.nativeKey(314))
        assertEquals(331, glfw.nativeKey(331))
        assertEquals(348, glfw.nativeKey(348))
        assertEquals(26, sdl.nativeKey(87))
        assertEquals(40, sdl.nativeKey(257))
        assertEquals(225, sdl.nativeKey(340))
        assertEquals(46, sdl.nativeKey(61))
        assertEquals(53, sdl.nativeKey(96))
        assertEquals(57, sdl.nativeKey(280))
        assertEquals(71, sdl.nativeKey(281))
        assertEquals(83, sdl.nativeKey(282))
        assertEquals(84, sdl.nativeKey(331))
        assertEquals(86, sdl.nativeKey(333))
        assertEquals(118, sdl.nativeKey(348))
        assertEquals(50, sdl.nativeKey(162))
    }

    @Test
    fun `key events use the correct second argument for each backend`() {
        val glfwEvent = backend(sdl = false).keyEvent(65, 2)
        val sdlEvent = backend(sdl = true).keyEvent(65, 192)

        assertEquals(65, recordInt(glfwEvent, 0))
        assertEquals(0, recordInt(glfwEvent, 1)) // 26.2 scancode
        assertEquals(2, recordInt(glfwEvent, 2))
        assertEquals(4, recordInt(sdlEvent, 0))
        assertEquals('a'.code, recordInt(sdlEvent, 1)) // 26.3 SDL keycode for shortcuts
        assertEquals(192, recordInt(sdlEvent, 2))
        val enterEvent = backend(sdl = true).keyEvent(257, 0)
        assertEquals(40, recordInt(enterEvent, 0))
        assertEquals(13, recordInt(enterEvent, 1))
    }

    @Test
    fun `mouse buttons and modifier masks use runtime constants`() {
        val glfw = backend(sdl = false)
        val sdl = backend(sdl = true)

        assertEquals(1, glfw.mouseButton(1))
        assertEquals(2, glfw.mouseButton(2))
        assertEquals(3, sdl.mouseButton(1))
        assertEquals(2, sdl.mouseButton(2))
        assertThrows(IllegalArgumentException::class.java) { sdl.mouseButton(8) }

        val heldCodes = setOf(340, 341)
        assertEquals(3, glfw.modifiers(heldCodes::contains))
        assertEquals(195, sdl.modifiers(heldCodes::contains))
        assertEquals(1, glfw.press)
        assertEquals(0, glfw.release)
    }

    @Test
    fun `unsupported runtime keys fail with a useful message`() {
        val backend = backend(sdl = true)
        val error = assertThrows(IllegalArgumentException::class.java) { backend.nativeKey(999) }
        assertTrue(error.message!!.contains("current Minecraft input backend"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { backend.nativeKey(314) }
            .message!!.contains("F25"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { backend.nativeKey(161) }
            .message!!.contains("WORLD_1"))
        assertEquals(50, backend.nativeKey(162))
    }

    @Test
    fun `runtime adapter reflects the loaded Minecraft input constants`() {
        fun actualConstant(name: String): Int = InputConstants::class.java.getField(name).getInt(null)
        fun actualNamedKey(name: String): Int = InputConstants.getKey(name).value

        val sdl = KeyEvent::class.java.recordComponents?.any { it.name == "keycode" } == true

        assertEquals(actualConstant("KEY_W"), InputCodes.nativeKey(87))
        assertEquals(actualConstant("MOUSE_BUTTON_RIGHT"), InputCodes.mouseButton(1))
        assertEquals(actualConstant("MOUSE_BUTTON_MIDDLE"), InputCodes.mouseButton(2))
        if (sdl) {
            assertEquals(actualNamedKey("key.keyboard.menu"), InputCodes.nativeKey(348))
            assertEquals(actualNamedKey("key.keyboard.world.2"), InputCodes.nativeKey(162))
            assertEquals(actualNamedKey("key.keyboard.keypad.divide"), InputCodes.nativeKey(331))
            assertEquals(actualNamedKey("key.keyboard.keypad.subtract"), InputCodes.nativeKey(333))
        }

        val event = InputCodes.keyEvent(87, 0)
        assertEquals(actualConstant("KEY_W"), recordInt(event, 0))
        assertEquals(if (sdl) actualConstant("KEYCODE_W") else 0, recordInt(event, 1))
    }

    private fun recordInt(value: Any, index: Int): Int =
        (value.javaClass.recordComponents[index].accessor.invoke(value) as Int)

    private fun backend(sdl: Boolean): InputCodeBackend {
        val keyNames = mapOf(
            65 to "A",
            87 to "W",
            61 to "EQUAL",
            96 to "GRAVE_ACCENT",
            331 to "KP_DIVIDE",
            333 to "KP_SUBTRACT",
            348 to "MENU",
            162 to "WORLD_2",
            257 to "ENTER",
            280 to "CAPS_LOCK",
            281 to "SCROLL_LOCK",
            282 to "NUM_LOCK",
            340 to "LEFT_SHIFT",
            341 to "LEFT_CONTROL",
            344 to "RIGHT_SHIFT",
            314 to "F25",
            161 to "WORLD_1"
        )
        val protocolCodes = keyNames.entries.associate { (code, name) -> name to code }
        val constants = if (sdl) {
            mapOf(
                "KEY_A" to 4, "KEY_W" to 26, "KEY_RETURN" to 40, "KEY_LSHIFT" to 225,
                "KEY_EQUALS" to 46, "KEY_GRAVE" to 53, "KEY_CAPSLOCK" to 57,
                "KEY_SCROLLLOCK" to 71, "KEY_NUMLOCK" to 83,
                "KEY_LCONTROL" to 224, "KEY_RSHIFT" to 229,
                "KEY_KP_DIVIDE" to 84, "KEY_KP_SUBTRACT" to 86,
                "KEY_MENU" to 118, "KEY_WORLD_2" to 50,
                "MOUSE_BUTTON_LEFT" to 1, "MOUSE_BUTTON_RIGHT" to 3, "MOUSE_BUTTON_MIDDLE" to 2,
                "MOD_SHIFT" to 3, "MOD_CONTROL" to 192, "MOD_ALT" to 768, "MOD_SUPER" to 3072,
                "PRESS" to 1, "RELEASE" to 0, "KEYCODE_A" to 'a'.code, "KEYCODE_RETURN" to 13
            )
        } else {
            mapOf(
                "KEY_A" to 65, "KEY_W" to 87, "KEY_RETURN" to 257, "KEY_LSHIFT" to 340,
                "KEY_EQUALS" to 61, "KEY_GRAVE" to 96, "KEY_CAPSLOCK" to 280,
                "KEY_SCROLLLOCK" to 281, "KEY_NUMLOCK" to 282,
                "KEY_LCONTROL" to 341, "KEY_RSHIFT" to 344,
                "MOUSE_BUTTON_LEFT" to 0, "MOUSE_BUTTON_RIGHT" to 1, "MOUSE_BUTTON_MIDDLE" to 2,
                "MOD_SHIFT" to 1, "MOD_CONTROL" to 2, "MOD_ALT" to 4, "MOD_SUPER" to 8,
                "PRESS" to 1, "RELEASE" to 0
            )
        }
        return InputCodeBackend(
            constant = constants::get,
            keyName = keyNames::get,
            protocolKey = protocolCodes::get,
            sdl = sdl
        )
    }
}
