package com.example.backroomscam.client;

import com.example.backroomscam.BackroomsCam;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.*;
import java.util.concurrent.CompletableFuture;
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

public class BackroomsCamClient implements ClientModInitializer {
    public static boolean cameraUp = false;   // camera raised (zoomed, overlay on)
    public static boolean recording = false;
    public static float zoom = 0f;            // 0..1, smoothed
    public static long recStartMs = 0;
    static int recTicks = 0, frame = 0;
    static Path recDir;
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

        if (recording && cameraUp && ++recTicks % 2 == 0) { // ~10 fps
            captureFrame(mc);
        }
    }

    private void startRecording(Minecraft mc) {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            recDir = mc.gameDirectory.toPath().resolve("camcorder").resolve("rec_" + stamp);
            Files.createDirectories(recDir);
            frame = 0; recTicks = 0;
            recStartMs = System.currentTimeMillis();
            recording = true;
        } catch (IOException e) {
            mc.player.displayClientMessage(Component.literal("Camcorder: couldn't create output folder"), true);
        }
    }

    private void stopRecording(Minecraft mc) {
        recording = false;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("Saved " + frame + " frames to " + recDir), false);
        }
    }

    private void captureFrame(Minecraft mc) {
        final Path out = recDir.resolve(String.format("frame_%05d.png", ++frame));
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> CompletableFuture.runAsync(() -> {
            try { image.writeToFile(out); }
            catch (IOException ignored) { }
            finally { image.close(); }
        }));
    }
}
