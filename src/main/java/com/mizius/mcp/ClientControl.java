package com.mizius.mcp;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import com.mizius.mcp.mixin.KeyboardAccess;
import com.mizius.mcp.mixin.MouseAccess;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.input.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

/** All mutable input state and game operations belong to the client thread. */
public final class ClientControl implements McpServer.Tools {
    private final Minecraft mc;
    private final Set<Integer> keys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> buttons = new HashSet<>();
    private final PriorityQueue<Scheduled> pending = new PriorityQueue<>(Comparator.comparingLong(Scheduled::due));
    private final Queue<CompletableFuture<JsonObject>> screenshots = new ArrayDeque<>();
    private volatile long leaseUntil;
    private static final long LEASE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private record Scheduled(long due, Runnable task) {}

    public ClientControl(Minecraft mc) { this.mc = mc; }
    public boolean active() { return System.nanoTime() < leaseUntil; }
    public boolean held(int key) { return keys.contains(key); }
    private void renew() { leaseUntil = System.nanoTime() + LEASE_NANOS; }
    private KeyboardAccess keyboard() { return (KeyboardAccess) mc.keyboardHandler; }
    private MouseAccess mouse() { return (MouseAccess) mc.mouseHandler; }
    private long window() { return mc.getWindow().handle(); }

    public void tick() {
        if (!active() && (!keys.isEmpty() || !buttons.isEmpty())) releaseAll();
        while (!pending.isEmpty() && pending.peek().due <= System.nanoTime()) pending.remove().task.run();
        // Focus loss and opening screens can reset Minecraft's key mappings.
        if (active() && mc.gui.screen() == null) {
            for (int key : keys) KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), true);
            for (int button : buttons) KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), true);
        }
    }
    private void later(long ms, Runnable task) {
        pending.add(new Scheduled(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms), task));
    }
    private int modifiers() {
        return ((held(GLFW.GLFW_KEY_LEFT_SHIFT) || held(GLFW.GLFW_KEY_RIGHT_SHIFT)) ? GLFW.GLFW_MOD_SHIFT : 0)
            | ((held(GLFW.GLFW_KEY_LEFT_CONTROL) || held(GLFW.GLFW_KEY_RIGHT_CONTROL)) ? GLFW.GLFW_MOD_CONTROL : 0)
            | ((held(GLFW.GLFW_KEY_LEFT_ALT) || held(GLFW.GLFW_KEY_RIGHT_ALT)) ? GLFW.GLFW_MOD_ALT : 0)
            | ((held(GLFW.GLFW_KEY_LEFT_SUPER) || held(GLFW.GLFW_KEY_RIGHT_SUPER)) ? GLFW.GLFW_MOD_SUPER : 0);
    }
    private void keyEvent(int code, boolean down) {
        if (down) keys.add(code); else keys.remove(code);
        keyboard().mcp$keyPress(window(), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, new KeyEvent(code, 0, modifiers()));
        if (!down) KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(code),false);
    }
    private void buttonEvent(int button, boolean down) {
        if (down) buttons.add(button); else buttons.remove(button);
        mouse().mcp$onButton(window(), new MouseButtonInfo(button, modifiers()), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE);
        if (!down) KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button),false);
    }
    private void releaseAll() {
        for (int key : List.copyOf(keys)) keyEvent(key, false);
        for (int button : List.copyOf(buttons)) buttonEvent(button, false);
    }
    private void move(double x, double y) {
        // Tools use framebuffer pixels, GLFW callbacks use window logical pixels.
        mouse().mcp$ignoreFirstMove(false);
        mouse().mcp$onMove(window(), x * mc.getWindow().getScreenWidth() / mc.getWindow().getWidth(),
            y * mc.getWindow().getScreenHeight() / mc.getWindow().getHeight());
        mc.mouseHandler.handleAccumulatedMovement();
    }

    @Override public CompletableFuture<JsonObject> call(String name, JsonObject args) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        mc.execute(() -> {
            if (result.isDone()) return;
            try {
                renew();
                switch (name) {
                    case "get_state" -> result.complete(McpServer.text(state()));
                    case "join" -> {
                        String ip = string(args, "ip", 255);
                        if (ip.isBlank()) throw new IllegalArgumentException("ip cannot be empty");
                        if (mc.level != null) mc.disconnectWithSavingScreen();
                        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(ip),
                            new ServerData("MCP", ip, ServerData.Type.OTHER), false, null);
                        result.complete(ok());
                    }
                    case "move" -> { move(number(args,"x"), number(args,"y")); result.complete(ok()); }
                    case "scroll" -> { mouse().mcp$onScroll(window(), 0, number(args,"amount")); result.complete(ok()); }
                    case "click" -> {
                        int button = button(args);
                        String action = args.has("action") ? string(args,"action",16) : "press";
                        if (!Set.of("press","hold","release").contains(action)) throw new IllegalArgumentException("Invalid action");
                        if (action.equals("press") && buttons.contains(button)) throw new IllegalArgumentException("Button already held");
                        move(number(args,"x"), number(args,"y"));
                        buttonEvent(button, !action.equals("release"));
                        if (action.equals("press")) later(75, () -> finishRelease(result, () -> buttonEvent(button,false)));
                        else result.complete(ok());
                    }
                    case "key" -> {
                        int key = keyCode(args.get("key"));
                        String action = string(args,"action",16);
                        if (!Set.of("press","hold","release").contains(action)) throw new IllegalArgumentException("Invalid action");
                        if (action.equals("press") && keys.contains(key)) throw new IllegalArgumentException("Key already held");
                        keyEvent(key, !action.equals("release"));
                        if (action.equals("press")) later(75, () -> finishRelease(result, () -> keyEvent(key,false)));
                        else result.complete(ok());
                    }
                    case "type" -> {
                        if (mc.gui.screen() == null) throw new IllegalStateException("Open a text interface first, for example with key T");
                        string(args,"text",32768).codePoints().forEach(cp -> keyboard().mcp$charTyped(window(),new CharacterEvent(cp)));
                        result.complete(ok());
                    }
                    case "execute_command" -> {
                        if (mc.getConnection() == null) throw new IllegalStateException("Not connected to a world");
                        String command = string(args,"command",32500);
                        if (command.startsWith("/")) command = command.substring(1);
                        if (command.isBlank() || command.contains("\n") || command.contains("\r")) throw new IllegalArgumentException("Invalid command");
                        mc.getConnection().sendCommand(command);
                        result.complete(ok());
                    }
                    case "drag" -> drag(args,result);
                    case "screenshot" -> screenshots.add(result);
                    default -> throw new IllegalArgumentException("Unknown tool: " + name);
                }
            } catch (Exception e) { result.completeExceptionally(e); }
        });
        return result;
    }
    private void finishRelease(CompletableFuture<JsonObject> result, Runnable release) {
        try { release.run(); result.complete(ok()); }
        catch (Exception e) { result.completeExceptionally(e); }
    }
    private void drag(JsonObject args, CompletableFuture<JsonObject> result) {
        JsonObject from = args.getAsJsonObject("from"), to = args.getAsJsonObject("to");
        double x1 = number(from,"x"), y1 = number(from,"y"), x2 = number(to,"x"), y2 = number(to,"y");
        int button = button(args);
        int duration = args.has("duration_ms") ? integer(args,"duration_ms",50,10000) : 250;
        if (buttons.contains(button)) throw new IllegalArgumentException("Button already held");
        move(x1,y1); buttonEvent(button,true);
        int steps = Math.max(2, duration / 50);
        for (int i = 1; i <= steps; i++) {
            final int step = i;
            later((long) duration * i / steps, () -> {
                try {
                    if (!result.isDone()) move(x1+(x2-x1)*step/steps,y1+(y2-y1)*step/steps);
                    if (step == steps || result.isDone()) {
                        buttonEvent(button,false);
                        result.complete(ok());
                    }
                } catch (Exception e) {
                    buttonEvent(button,false);
                    result.completeExceptionally(e);
                }
            });
        }
    }
    private void screenshot(CompletableFuture<JsonObject> result) {
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                BufferedImage png = new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                for (int y=0; y<image.getHeight(); y++) for (int x=0; x<image.getWidth(); x++) png.setRGB(x,y,image.getPixel(x,y));
                ImageIO.write(png,"png",output);
                JsonObject content = new JsonObject();
                content.addProperty("type","image"); content.addProperty("mimeType","image/png");
                content.addProperty("data",Base64.getEncoder().encodeToString(output.toByteArray()));
                JsonArray contents = new JsonArray(); contents.add(content);
                JsonObject response = new JsonObject(); response.add("content",contents);
                result.complete(response);
            } catch (Exception e) { result.completeExceptionally(e); }
        });
    }
    public void frame() {
        while (!screenshots.isEmpty()) {
            CompletableFuture<JsonObject> result=screenshots.remove();
            if (!result.isDone()) {
                try { screenshot(result); } catch (Exception e) { result.completeExceptionally(e); }
            }
        }
    }
    private JsonObject state() {
        JsonObject s = new JsonObject();
        s.addProperty("version",net.minecraft.SharedConstants.getCurrentVersion().name());
        s.addProperty("width",mc.getWindow().getWidth()); s.addProperty("height",mc.getWindow().getHeight());
        s.addProperty("gui_width",mc.getWindow().getGuiScaledWidth()); s.addProperty("gui_height",mc.getWindow().getGuiScaledHeight());
        s.addProperty("window_width",mc.getWindow().getScreenWidth()); s.addProperty("window_height",mc.getWindow().getScreenHeight());
        s.addProperty("mouse_x",mc.mouseHandler.xpos() * mc.getWindow().getWidth() / mc.getWindow().getScreenWidth());
        s.addProperty("mouse_y",mc.mouseHandler.ypos() * mc.getWindow().getHeight() / mc.getWindow().getScreenHeight());
        s.addProperty("screen",mc.gui.screen() == null ? "gameplay" : mc.gui.screen().getClass().getSimpleName());
        s.addProperty("connected",mc.getConnection()!=null); s.addProperty("in_world",mc.level!=null);
        s.addProperty("paused",mc.isPaused()); s.addProperty("window_focused",mc.getWindow().isFocused());
        s.addProperty("mouse_grabbed",mc.mouseHandler.isMouseGrabbed());
        s.addProperty("held_input_timeout_ms",30000);
        JsonArray held = new JsonArray(); keys.stream().sorted().forEach(held::add); s.add("held_keys",held);
        JsonArray mouse = new JsonArray(); buttons.stream().sorted().forEach(mouse::add); s.add("held_buttons",mouse);
        if (mc.getCurrentServer()!=null) s.addProperty("server",mc.getCurrentServer().ip);
        if (mc.player!=null) {
            s.addProperty("x",mc.player.getX()); s.addProperty("y",mc.player.getY()); s.addProperty("z",mc.player.getZ());
            s.addProperty("yaw",mc.player.getYRot()); s.addProperty("pitch",mc.player.getXRot());
        }
        return s;
    }
    private static JsonObject ok() { return McpServer.text("ok"); }
    static String string(JsonObject args, String field, int max) {
        JsonElement v = args.get(field);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(field+" must be a string");
        String s=v.getAsString(); if (s.length()>max) throw new IllegalArgumentException(field+" is too long"); return s;
    }
    static double number(JsonObject args, String field) {
        if (args == null) throw new IllegalArgumentException("Missing coordinates");
        JsonElement v=args.get(field);
        if (v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field+" must be a number");
        double d=v.getAsDouble(); if (!Double.isFinite(d) || Math.abs(d)>1_000_000) throw new IllegalArgumentException(field+" out of range"); return d;
    }
    static int integer(JsonObject args, String field, int min, int max) {
        double n=number(args,field); if(n != Math.rint(n) || n<min || n>max) throw new IllegalArgumentException(field+" out of range"); return (int)n;
    }
    static int button(JsonObject args) {
        JsonElement b=args.get("button");
        if (b!=null && b.isJsonPrimitive() && b.getAsJsonPrimitive().isString()) return switch(b.getAsString().toLowerCase(Locale.ROOT)) {
            case "left" -> 0; case "right" -> 1; case "middle" -> 2;
            default -> throw new IllegalArgumentException("Unknown button");
        };
        return integer(args,"button",0,7);
    }
    static int keyCode(JsonElement key) {
        if (key==null || !key.isJsonPrimitive()) throw new IllegalArgumentException("Missing key");
        if (key.getAsJsonPrimitive().isNumber()) {
            int n=key.getAsInt(); if(n<32 || n>348 || n!=key.getAsDouble()) throw new IllegalArgumentException("Invalid GLFW key code"); return n;
        }
        String name=key.getAsString().toUpperCase(Locale.ROOT).replace(' ','_');
        name=switch(name) { case "CTRL","CONTROL" -> "LEFT_CONTROL"; case "SHIFT" -> "LEFT_SHIFT"; case "ALT" -> "LEFT_ALT"; case "ESC" -> "ESCAPE"; case "RETURN" -> "ENTER"; default -> name; };
        try { return GLFW.class.getField("GLFW_KEY_"+name).getInt(null); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Unknown key: "+name); }
    }
}

