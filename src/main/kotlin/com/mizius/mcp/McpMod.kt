package com.mizius.mcp

import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

class McpMod : ClientModInitializer {
    override fun onInitializeClient() {
        val minecraft = Minecraft.getInstance()
        val clientControl = ClientControl(minecraft)
        control = clientControl
        try {
            val mcpServer = McpServer(clientControl, minecraft.gameDirectory.toPath())
            server = mcpServer
            mcpServer.start()
            logger.info("Minecraft MCP listening on http://127.0.0.1:{}/mcp", mcpServer.port())
        } catch (exception: Exception) {
            logger.error("Could not start Minecraft MCP server", exception)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("minecraft-mcp")
        private var control: ClientControl? = null
        private var server: McpServer? = null

        @JvmStatic fun isControlling(): Boolean = control?.active() == true
        @JvmStatic fun isHeld(key: Int): Boolean = control?.held(key) == true
        @JvmStatic fun tick() { control?.tick() }
        @JvmStatic fun frame() { control?.frame() }
        @JvmStatic fun close() { server?.close() }
    }
}
