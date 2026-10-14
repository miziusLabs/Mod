package com.mizius.mcp.mixin;
import com.mizius.mcp.McpMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void mcp$tick(CallbackInfo ci) { McpMod.tick(); }
    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void mcp$frame(boolean renderLevel, CallbackInfo ci) { McpMod.frame(); }
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void mcp$active(CallbackInfoReturnable<Boolean> cir) {
        if (McpMod.isControlling()) cir.setReturnValue(true);
    }
    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void mcp$pause(CallbackInfo ci) {
        if (McpMod.isControlling()) ci.cancel();
    }
    @Inject(method = "close", at = @At("HEAD"))
    private void mcp$close(CallbackInfo ci) { McpMod.close(); }
}
