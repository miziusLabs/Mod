package com.mizius.mcp;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McpMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft-mcp");
    private static ClientControl control;
    private static McpServer server;

    @Override public void onInitializeClient() {
        control = new ClientControl(Minecraft.getInstance());
        try {
            server = new McpServer(control,Minecraft.getInstance().gameDirectory.toPath());
            server.start();
            LOGGER.info("Minecraft MCP listening on http://127.0.0.1:{}/mcp", server.port());
        } catch (Exception e) {
            LOGGER.error("Could not start Minecraft MCP server", e);
        }
    }
    public static boolean isControlling() { return control != null && control.active(); }
    public static boolean isHeld(int key) { return control != null && control.held(key); }
    public static void tick() { if (control != null) control.tick(); }
    public static void frame() { if (control != null) control.frame(); }
    public static void close() { if (server != null) server.close(); }
}
