package com.example.backroomscam.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class CamOverlay {
    private static final int WHITE = 0xFFFFFFFF, RED = 0xFFE02020;

    public static void render(GuiGraphics g, DeltaTracker dt) {
        if (!BackroomsCamClient.cameraUp) return;
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), h = g.guiHeight();

        // old-tape yellow tint + scanlines
        g.fill(0, 0, w, h, 0x1A8C7A2A);
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x16000000);

        // vignette
        int n = 28;
        for (int i = 0; i < n; i++) {
            int a = (int) (130 * (1f - i / (float) n));
            int c = a << 24;
            g.fill(0, i, w, i + 1, c);
            g.fill(0, h - i - 1, w, h - i, c);
            g.fill(i, 0, i + 1, h, c);
            g.fill(w - i - 1, 0, w - i, h, c);
        }

        // viewfinder corner brackets
        int m = 14, L = 16, t = 2;
        corner(g, m, m, L, t, 1, 1);
        corner(g, w - m, m, L, t, -1, 1);
        corner(g, m, h - m, L, t, 1, -1);
        corner(g, w - m, h - m, L, t, -1, -1);

        // top-left status
        boolean rec = BackroomsCamClient.recording;
        if (rec) {
            long s = (System.currentTimeMillis() - BackroomsCamClient.recStartMs) / 1000;
            if ((System.currentTimeMillis() / 500) % 2 == 0) g.fill(m + 8, m + 10, m + 16, m + 18, RED);
            g.drawString(mc.font, "REC", m + 22, m + 10, RED);
            g.drawString(mc.font, String.format("%02d:%02d", s / 60, s % 60), m + 8, m + 24, WHITE);
            g.drawString(mc.font, BackroomsCamClient.micOn ? "MIC ON" : "NO MIC", m + 8, m + 38, WHITE);
        } else {
            g.drawString(mc.font, "STBY", m + 8, m + 10, WHITE);
        }

        // bottom-right zoom readout
        String zoomText = String.format("ZOOM %.1fx", 1.0f / BackroomsCamClient.fovMultiplier());
        g.drawString(mc.font, zoomText, w - m - 8 - mc.font.width(zoomText), h - m - 14, WHITE);

        // top-right battery
        g.drawString(mc.font, "BAT [|||]", w - m - 56, m + 10, WHITE);

        // bottom-left date / time stamp
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm:ss a"));
        g.drawString(mc.font, time, m + 8, h - m - 26, WHITE);
        g.drawString(mc.font, "JUL. 14 1996", m + 8, h - m - 14, WHITE);
    }

    private static void corner(GuiGraphics g, int x, int y, int len, int t, int dx, int dy) {
        g.fill(Math.min(x, x + dx * len), Math.min(y, y + dy * t), Math.max(x, x + dx * len), Math.max(y, y + dy * t), WHITE);
        g.fill(Math.min(x, x + dx * t), Math.min(y, y + dy * len), Math.max(x, x + dx * t), Math.max(y, y + dy * len), WHITE);
    }
}
