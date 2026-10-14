package com.mizius.mcp.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mizius.mcp.McpMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow public abstract int getWidth();
    @Shadow public abstract int getHeight();

    // GLFW can report zero logical dimensions for an unfocused/minimized window.
    // Keep Minecraft's own GUI coordinate conversion usable for virtual input.
    @Inject(method="getScreenWidth",at=@At("RETURN"),cancellable=true)
    private void mcp$width(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValue()<=0 && McpMod.isControlling()) cir.setReturnValue(Math.max(1,getWidth()));
    }
    @Inject(method="getScreenHeight",at=@At("RETURN"),cancellable=true)
    private void mcp$height(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValue()<=0 && McpMod.isControlling()) cir.setReturnValue(Math.max(1,getHeight()));
    }
}
