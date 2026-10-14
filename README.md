<h1 align="center">
  <br>
  Minecraft MCP
  <br>
</h1>

<h4 align="center">A Fabric client mod that lets agents control Minecraft through a local MCP server.</h4>

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-%3E%3D25-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.0-blue?style=flat-square">
  <img alt="Minecraft" src="https://img.shields.io/badge/Minecraft-26.2-62B47A?style=flat-square">
  <img alt="Fabric Loader" src="https://img.shields.io/badge/Fabric_Loader-%3E%3D0.19.3-DBD0B4?style=flat-square">
</p>

## Overview

Minecraft MCP is a Java 25 Fabric mod for Minecraft Java 26.2. It exposes gameplay, menus, inventories, dialogs, chat and client logs through MCP at `http://127.0.0.1:8765/mcp`.

The server runs inside Minecraft and is available while the client is running. Launch your Fabric instance normally, then connect an agent using Streamable HTTP. Game interactions use Minecraft's internal APIs and execute on the client thread.

## Features

- Controls the keyboard and mouse in gameplay and GUI screens.
- Supports simultaneous held keys, held mouse buttons and dragging over multiple ticks.
- Returns screenshots as MCP PNG image content.
- Connects to multiplayer servers and sends Minecraft commands.
- Returns client state, screen dimensions, held inputs and player position.
- Searches client logs with text queries and a line limit.
- Runs ordered action batches with optional delays.
- Supports background interactions where Minecraft can keep processing input and rendering.
- Binds to IPv4 loopback and supports optional bearer-token authentication.
- Uses Minecraft's existing dependencies, with no Fabric API requirement or companion process.

## Requirements

- Minecraft Java 26.2.
- Java 25 or newer.
- Fabric Loader 0.19.3 or newer.
- An MCP host that supports Streamable HTTP.
- An authenticated Minecraft account for multiplayer servers that require one.

## Setup

1. Build the mod from the repository root.
2. Copy `build/libs/minecraft-mcp-1.0.0.jar` into the `mods` folder of your Fabric 26.2 instance.
3. Launch Minecraft normally.
4. Configure your MCP host to connect to `http://127.0.0.1:8765/mcp`.

```powershell
.\gradlew.bat build
```

The sources JAR is for development and should not go into `mods`.

## Configuration

For MCP hosts that use the `mcpServers` configuration format, a connection can look like this. The transport setting may vary by host.

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

### Server settings

- `minecraft.mcp.port` - JVM property for the local port, default `8765`. For example, `-Dminecraft.mcp.port=8766`.
- `MINECRAFT_MCP_TOKEN` - Optional environment variable that enables bearer-token authentication.
- `minecraft.mcp.token` - JVM property that overrides the environment token.

When you configure a token, configure your MCP host to send `Authorization: Bearer <token>`. Requests from browser origins and requests with unexpected Host headers are rejected. The server binds only to `127.0.0.1`.

The server supports MCP 2026-07-28 discovery and the 2025-03-26, 2025-06-18 and 2025-11-25 initialization handshake. It returns JSON responses and does not need an SSE connection or protocol session.

## Usage

| Tool | Arguments and behavior |
| --- | --- |
| `join` | `ip`, including an optional port. Begins connecting and returns immediately. Check state or screenshots for connection progress and dialogs. |
| `screenshot` | No arguments. Returns MCP `image` content with a base64 PNG of the framebuffer. |
| `click` | `x`, `y`, `button`. Optional `action` is `press`, `hold` or `release`, default `press`. |
| `move` | `x`, `y`. Moves the virtual pointer. In gameplay the displacement turns the camera. |
| `drag` | `from` and `to`, each `{ "x": 0, "y": 0 }`, plus `button`. Optional `duration_ms` defaults to 250, with a range of 50 to 10000. |
| `scroll` | `amount`. Positive scrolls up and negative scrolls down. |
| `key` | `key` and `action`. `press`, `hold` or `release`; names such as `W`, `ENTER`, `LEFT_SHIFT`, `CTRL`, or a GLFW key code. |
| `type` | `text`. Sends Unicode character events to the open interface. |
| `execute_command` | `command`, with or without `/`. Sends a command using the connection. Server permissions still apply. |
| `get_state` | No arguments. Returns framebuffer and GUI dimensions, screen, connection, focus, held inputs and player position. |
| `get_logs` | Optional `queries`, an array of case-insensitive literal substrings, and `limit`, an integer from 1 to 1000, default 100. Returns the latest matching lines from `logs/latest.log`. |
| `batch` | `actions`, an ordered list of `{ "tool": "key", "arguments": {...}, "delay_ms": 0 }`. Delay is optional and occurs before the action. |

Mouse buttons can be `left`, `right`, `middle` or integers 0 through 7. Coordinates use screenshot pixels with the origin at the top left. The mod converts them to window coordinates, then Minecraft handles GUI scaling. For camera movement, use several moves with the desired displacement between coordinates.

Several keys can stay held simultaneously. For example, hold `W` and `LEFT_SHIFT`, wait or perform other actions, then release each. Use `click` with `hold`, several `move` calls and `click` with `release` for a drag spread over tool calls. `drag` performs that sequence over multiple client ticks. Presses stay down long enough for gameplay ticks to observe them.

Held inputs and background control expire after 30 seconds without a game tool call. `get_state` renews that timeout. Release each held input when finished. Screen changes can consume input or pause gameplay, as they do during normal play. Held gameplay mappings resume when the screen closes.

To write chat, press `T`, type text, then press `ENTER`. Inventories, menus and dialogs use the same input tools. The mod does not select items or bypass GUI behavior on the agent's behalf.

## Sequences

This batch holds two movement keys together, moves for one second, then releases both and takes a screenshot.

```json
{
  "name": "batch",
  "arguments": {
    "actions": [
      { "tool": "key", "arguments": { "key": "W", "action": "hold" } },
      { "tool": "key", "arguments": { "key": "LEFT_SHIFT", "action": "hold" } },
      { "tool": "key", "arguments": { "key": "W", "action": "release" }, "delay_ms": 1000 },
      { "tool": "key", "arguments": { "key": "LEFT_SHIFT", "action": "release" } },
      { "tool": "screenshot" }
    ]
  }
}
```

Batches contain at most 128 actions and 8 screenshots. Each delay can be up to 10000 ms, and combined delays and drag durations can be at most 20000 ms. Execution stops after 30 seconds. Nested batches are rejected. A sequence stops at the first error, with earlier actions still applied. Release any inputs held by a failed sequence or allow the idle timeout to release them. Other calls cannot interleave a sequence. Concurrent callers receive a retryable tool error when an action is already running.

## Client logs

`get_logs` returns lines in chronological order from the latest matches. An empty query list returns the log tail. A line matches when it contains any query, ignoring case. Queries are literal text, so `[` and other regex characters have no special meaning. You can pass at most 16 queries, each up to 256 characters.

```json
{
  "name": "get_logs",
  "arguments": {
    "queries": ["error", "warn", "minecraft-mcp"],
    "limit": 50
  }
}
```

The response contains `lines`, `returned`, `limit`, `truncated`, `scanned_bytes`, `file_bytes` and the fixed file name `logs/latest.log`. The tool scans at most the most recent 16 MiB of the current client's log. `truncated` is true if the scan skipped older bytes or the line limit omitted matches. Missing logs return a tool error. Log searches run on HTTP workers, remain available while game actions run, and do not renew the held-input timeout. Log searches can also appear in a batch.

## Interaction flow

All game interactions run on the client thread. Tick scheduling handles presses, dragging and held-input expiry. Screenshot requests capture after a rendered frame, using Minecraft's backend-independent GPU readback. The screenshot callback closes its native image after encoding the PNG.

During an active agent control lease, the mod suppresses Minecraft's pause-on-focus-loss behavior and treats input callbacks as active. It also exposes held virtual keys to Minecraft's key polling. Input tools do not use OS input injection. Some normal Minecraft operations, such as grabbing the mouse or toggling fullscreen, can still change native window state. A minimized window, suspended render loop or explicit pause screen can prevent rendering or movement. A client action times out after 15 seconds and returns a tool error.

The endpoint represents one client and one shared set of inputs. Multiple agents should coordinate ownership of that instance.

## Development

Launch a development client from the repository root. This instance does not provide an authenticated multiplayer account.

```powershell
.\gradlew.bat runClient
```

Run the test suite with Gradle.

```powershell
.\gradlew.bat test
```

Protocol tests cover discovery, routing metadata, batch order, failure handling, request limits, browser-origin rejection and optional tokens. Log tests cover search, line limits, Unicode, scan limits and log rotation. Live gameplay checks require a working graphics driver and a Minecraft instance; multiplayer checks also require an authenticated account and a reachable server.

Project entry point - `src/main/java/com/mizius/mcp/McpMod.java`.

The build follows [Fabric's Minecraft 26.2 toolchain guidance](https://www.fabricmc.net/2026/06/15/262.html). Protocol behavior follows the [MCP 2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28).
