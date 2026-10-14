package com.mizius.mcp.mixin

import com.mizius.mcp.McpMod
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Minecraft::class)
abstract class MinecraftMixin {
    @Inject(method = ["tick"], at = [At("HEAD")])
    private fun `mcp$tick`(ci: CallbackInfo) { McpMod.tick() }

    @Inject(method = ["renderFrame"], at = [At("TAIL")])
    private fun `mcp$frame`(renderLevel: Boolean, ci: CallbackInfo) { McpMod.frame() }

    @Inject(method = ["isWindowActive"], at = [At("HEAD")], cancellable = true)
    private fun `mcp$active`(ci: CallbackInfoReturnable<Boolean>) {
        if (McpMod.isControlling()) ci.returnValue = true
    }

    @Inject(method = ["pauseIfInactive"], at = [At("HEAD")], cancellable = true)
    private fun `mcp$pause`(ci: CallbackInfo) {
        if (McpMod.isControlling()) ci.cancel()
    }

    @Inject(method = ["close"], at = [At("HEAD")])
    private fun `mcp$close`(ci: CallbackInfo) { McpMod.close() }
}
