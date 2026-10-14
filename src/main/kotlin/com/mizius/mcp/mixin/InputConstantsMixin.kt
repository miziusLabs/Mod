// Emit a class annotation on the file facade so the static mixin has no singleton fields.
@file:Suppress("INAPPLICABLE_TARGET")
@file:JvmName("InputConstantsMixin")
@file:Mixin(InputConstants::class)

package com.mizius.mcp.mixin

import com.mizius.mcp.McpMod
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.Window
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Inject(method = ["isKeyDown"], at = [At("HEAD")], cancellable = true)
private fun `mcp$keyDown`(window: Window, key: Int, ci: CallbackInfoReturnable<Boolean>) {
    if (McpMod.isHeld(key)) ci.returnValue = true
}
