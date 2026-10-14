package com.mizius.mcp.mixin;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mizius.mcp.McpMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void mcp$keyDown(Window window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (McpMod.isHeld(key)) cir.setReturnValue(true);
    }
}
