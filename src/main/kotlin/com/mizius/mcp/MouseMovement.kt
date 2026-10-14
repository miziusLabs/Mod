package com.mizius.mcp

import net.minecraft.client.MouseHandler
import java.lang.reflect.InvocationTargetException

/** SDL supplies relative motion separately; GLFW derives it from the cursor position. */
internal object MouseMovement {
    private val onMove = MouseHandler::class.java.declaredMethods.single {
        it.name == "onMove" && it.parameterCount in setOf(3, 5)
    }.apply { isAccessible = true }

    fun move(mouse: MouseHandler, window: Long, x: Double, y: Double) {
        try {
            if (onMove.parameterCount == 5) {
                onMove.invoke(mouse, window, x, y, x - mouse.xpos(), y - mouse.ypos())
            } else {
                onMove.invoke(mouse, window, x, y)
            }
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    }
}
