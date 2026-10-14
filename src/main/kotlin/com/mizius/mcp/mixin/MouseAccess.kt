package com.mizius.mcp.mixin

import net.minecraft.client.MouseHandler
import net.minecraft.client.input.MouseButtonInfo
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(MouseHandler::class)
interface MouseAccess {
    @Invoker("onButton")
    fun `mcp$onButton`(window: Long, button: MouseButtonInfo, action: Int)

    @Invoker("onMove")
    fun `mcp$onMove`(window: Long, x: Double, y: Double)

    @Invoker("onScroll")
    fun `mcp$onScroll`(window: Long, horizontal: Double, vertical: Double)

    @Accessor("ignoreFirstMove")
    fun `mcp$ignoreFirstMove`(ignore: Boolean)
}
