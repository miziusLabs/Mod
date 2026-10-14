package com.mizius.mcp.mixin

import net.minecraft.client.KeyboardHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(KeyboardHandler::class)
interface KeyboardAccess {
    @Invoker("keyPress")
    fun `mcp$keyPress`(window: Long, action: Int, event: KeyEvent)

    @Invoker("charTyped")
    fun `mcp$charTyped`(window: Long, event: CharacterEvent)
}
