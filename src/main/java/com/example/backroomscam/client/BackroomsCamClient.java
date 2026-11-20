package com.example.backroomscam.client;

import com.example.backroomscam.BackroomsCam;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class BackroomsCamClient implements ClientModInitializer {
    // Video settings: a frame every TICKS_PER_FRAME game ticks (20 ticks/sec). 2 -> 10 fps, 1 -> 20 fps.
    static final int TICKS_PER_FRAME = 2;
    static final int FPS = 20 / TICKS_PER_FRAME;

    public static boolean cameraUp = false;   // camera raised (zoomed, overlay on)
    public static boolean recording = false;
    public static float zoom = 0f;            // 0..1, smoothed
    public static long recStartMs = 0;

    static int recTicks = 0;
    static Path recFile;
    static AviWriter avi;
    static ExecutorService encoder;
    static final AtomicInteger pending = new AtomicInteger();
    static boolean prevUse = false, prevAttack = false;

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

        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath(BackroomsCam.MOD_ID, "camcorder_overlay"),
                CamOverlay::render);
    }

    private void tick(Minecraft mc) {
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

        if (recording && cameraUp && ++recTicks % TICKS_PER_FRAME == 0) {
            captureFrame(mc);
        }
    }

    private void startRecording(Minecraft mc) {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path dir = mc.gameDirectory.toPath().resolve("camcorder");
            Files.createDirectories(dir);
            recFile = dir.resolve("rec_" + stamp + ".avi");
            avi = new AviWriter(recFile, FPS);
            encoder = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "camcorder-encoder");
                t.setDaemon(true);
                return t;
            });
            pending.set(0);
            recTicks = 0;
            recStartMs = System.currentTimeMillis();
            recording = true;
        } catch (IOException e) {
            mc.player.displayClientMessage(Component.literal("Camcorder: couldn't create output folder"), true);
        }
    }

    private void stopRecording(Minecraft mc) {
        recording = false;
        final AviWriter writer = avi;
        final ExecutorService ex = encoder;
        final Path file = recFile;
        avi = null;
        encoder = null;
        if (writer == null || ex == null) return;

        // Runs after every queued frame has been encoded, then finalizes the video file.
        ex.execute(() -> {
            String msg;
            try {
                writer.close();
                msg = writer.frameCount() > 0
                        ? "Saved video (" + writer.frameCount() + " frames): " + file
                        : "Camcorder: recording was empty, nothing saved";
            } catch (IOException e) {
                msg = "Camcorder: failed to save video (" + e.getMessage() + ")";
            }
            final String finalMsg = msg;
            mc.execute(() -> {
                if (mc.player != null) mc.player.displayClientMessage(Component.literal(finalMsg), false);
            });
        });
        ex.shutdown();
    }

    private void captureFrame(Minecraft mc) {
        if (pending.get() > 8) return; // encoder is behind; skip a frame instead of eating memory
        final AviWriter writer = avi;
        final ExecutorService ex = encoder;
        if (writer == null || ex == null) return;
        pending.incrementAndGet();
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
            try {
                ex.execute(() -> {
                    try {
                        // NativeImage can write itself as a PNG file; read that back as bytes.
                        Path tmp = Files.createTempFile("camcorder", ".png");
                        try {
                            image.writeToFile(tmp);
                            writer.addPng(Files.readAllBytes(tmp));
                        } finally {
                            Files.deleteIfExists(tmp);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        image.close();
                        pending.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) { // recording already stopped
                image.close();
                pending.decrementAndGet();
            }
        });
    }
}
