package com.mizius.mcp.mixin;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(MouseHandler.class)
public interface MouseAccess {
    @Invoker("onButton") void mcp$onButton(long window, MouseButtonInfo button, int action);
    @Invoker("onMove") void mcp$onMove(long window, double x, double y);
    @Invoker("onScroll") void mcp$onScroll(long window, double horizontal, double vertical);
    @Accessor("ignoreFirstMove") void mcp$ignoreFirstMove(boolean ignore);
}
