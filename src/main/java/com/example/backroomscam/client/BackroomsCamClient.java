package com.example.backroomscam.client;

import com.example.backroomscam.BackroomsCam;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class BackroomsCamClient implements ClientModInitializer {
    // ---- settings you can tweak ----
    static final int FPS = 20;                     // video frame rate (the game only gives us 20 new frames a second)
    static final int MAX_WIDTH = 1280;             // video is shrunk to fit this width: smoother + smaller files
    static final boolean RECORD_AUDIO = true;      // record the microphone
    static final String MIC_DEVICE_HINT = "";      // "" = default microphone. On Windows, "Stereo Mix" records game sound if you enable it.
    static final boolean PIXELS_ARE_ABGR = false;  // set true if red and blue look swapped in the video

    // ---- state ----
    public static boolean cameraUp = false;        // camera raised (zoomed, overlay on)
    public static boolean recording = false;
    public static boolean micOn = false;
    public static float zoom = 0f;                 // 0..1 raise animation
    public static float zoomLevel = 0.75f;         // 0..1 set with the scroll wheel
    public static float zoomSmooth = 0.75f;        // smoothed zoomLevel
    public static long recStartMs = 0;

    static Recorder recorder;
    static Path recFile;
    static boolean prevUse = false, prevAttack = false;
    static boolean scrollHooked = false;
    static GLFWScrollCallback prevScroll;

    /** Multiplier applied to the field of view (1 = no zoom). */
    public static float fovMultiplier() {
        return 1.0f - 0.85f * zoomSmooth * zoom;
    }

    static boolean holding(Player p) {
        return p.getMainHandItem().is(BackroomsCam.CAMCORDER) || p.getOffhandItem().is(BackroomsCam.CAMCORDER);
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        // Camera mode owns both buttons: no mining/interacting while holding the camcorder.
        AttackBlockCallback.EVENT.register((p, w, h, pos, dir) -> holding(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((p, w, h, e, hit) -> holding(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((p, w, h, hit) -> holding(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((p, w, h, e, hit) -> holding(p) ? InteractionResult.FAIL : InteractionResult.PASS);

        // Hide the hotbar and the other HUD bits while the camera is raised.
        // If the build says one of these names doesn't exist, just delete that line.
        for (var id : java.util.List.of(
                VanillaHudElements.HOTBAR,
                VanillaHudElements.CROSSHAIR,
                VanillaHudElements.ARMOR_BAR,
                VanillaHudElements.HEALTH_BAR,
                VanillaHudElements.FOOD_BAR,
                VanillaHudElements.AIR_BAR,
                VanillaHudElements.INFO_BAR,
                VanillaHudElements.EXPERIENCE_LEVEL,
                VanillaHudElements.HELD_ITEM_TOOLTIP)) {
            HudElementRegistry.replaceElement(id, original -> (graphics, tickCounter) -> {
                if (!cameraUp) original.render(graphics, tickCounter);
            });
        }

        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath(BackroomsCam.MOD_ID, "camcorder_overlay"),
                CamOverlay::render);
    }

    // ---- scroll wheel zoom ----
    private static void hookScroll() {
        scrollHooked = true;
        try {
            long window = GLFW.glfwGetCurrentContext(); // the game window (we're on the render thread)
            if (window != 0L) prevScroll = GLFW.glfwSetScrollCallback(window, BackroomsCamClient::onScroll);
        } catch (Throwable ignored) {
        }
    }

    private static void onScroll(long window, double dx, double dy) {
        if (cameraUp && dy != 0) { // camera raised: scroll zooms instead of changing hotbar slot
            zoomLevel = Math.max(0f, Math.min(1f, zoomLevel + (float) dy * 0.06f));
            return;
        }
        if (prevScroll != null) prevScroll.invoke(window, dx, dy);
    }

    // ---- per tick ----
    private void tick(Minecraft mc) {
        if (!scrollHooked) hookScroll();

        var p = mc.player;
        boolean holding = p != null && mc.screen == null && holding(p);

        boolean use = holding && mc.options.keyUse.isDown();
        boolean attack = holding && mc.options.keyAttack.isDown();

        if (holding) {
            if (use && !prevUse) {            // right-click: raise / lower camera
                cameraUp = !cameraUp;
                if (!cameraUp && recording) stopRecording(mc);
            }
            if (cameraUp && attack && !prevAttack) { // left-click: record on/off
                if (recording) stopRecording(mc); else startRecording(mc);
            }
        } else if (cameraUp) {
            cameraUp = false;
            if (recording) stopRecording(mc);
        }
        prevUse = use;
        prevAttack = attack;

        zoom += ((cameraUp ? 1f : 0f) - zoom) * 0.3f;
        zoomSmooth += (zoomLevel - zoomSmooth) * 0.4f;

        if (recording && cameraUp) captureFrame(mc);
    }

    // ---- recording ----
    private void startRecording(Minecraft mc) {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path dir = mc.gameDirectory.toPath().resolve("camcorder");
            Files.createDirectories(dir);
            recFile = dir.resolve("rec_" + stamp + ".avi");
        } catch (IOException e) {
            mc.player.displayClientMessage(Component.literal("Camcorder: couldn't create output folder"), true);
            return;
        }
        MicCapture mic = RECORD_AUDIO ? MicCapture.tryStart(MIC_DEVICE_HINT) : null;
        if (RECORD_AUDIO && mic == null) {
            mc.player.displayClientMessage(Component.literal("Camcorder: no microphone available, recording video only"), true);
        }
        recorder = new Recorder(recFile, FPS, mic, MicCapture.RATE);
        micOn = mic != null;
        recStartMs = System.currentTimeMillis();
        recording = true;
    }

    private void stopRecording(Minecraft mc) {
        recording = false;
        micOn = false;
        Recorder r = recorder;
        recorder = null;
        if (r == null) return;
        r.finish(msg -> mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
        }));
    }

    private void captureFrame(Minecraft mc) {
        Recorder r = recorder;
        if (r == null || r.pending() >= 6) return;   // encoder busy: the next frame will repeat to keep timing
        int due = r.reserve();
        if (due <= 0) return;
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
            boolean ok = r.submit(() -> {
                try {
                    return convert(image);
                } finally {
                    image.close();
                }
            }, due);
            if (!ok) image.close();
        });
    }

    /** NativeImage -> int[] RGB, shrunk so the width is at most MAX_WIDTH. Runs on a worker thread. */
    private static Recorder.Frame convert(NativeImage image) {
        int sw = image.getWidth(), sh = image.getHeight();
        int step = Math.max(1, (sw + MAX_WIDTH - 1) / MAX_WIDTH);
        int w = sw / step, h = sh / step;
        int[] out = new int[w * h];
        int n = step * step;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < step; dy++) {
                    for (int dx = 0; dx < step; dx++) {
                        int px = image.getPixel(x * step + dx, y * step + dy);
                        r += (px >> 16) & 0xFF;
                        g += (px >> 8) & 0xFF;
                        b += px & 0xFF;
                    }
                }
                r /= n; g /= n; b /= n;
                out[y * w + x] = PIXELS_ARE_ABGR ? (b << 16) | (g << 8) | r : (r << 16) | (g << 8) | b;
            }
        }
        return new Recorder.Frame(out, w, h);
    }
}
