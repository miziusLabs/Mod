package com.mizius.mcp.mixin;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(KeyboardHandler.class)
public interface KeyboardAccess {
    @Invoker("keyPress") void mcp$keyPress(long window, int action, KeyEvent event);
    @Invoker("charTyped") void mcp$charTyped(long window, CharacterEvent event);
}
