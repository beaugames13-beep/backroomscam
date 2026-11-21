package com.example.backroomscam.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class CamOverlay {
    private static final int WHITE = 0xFFFFFFFF, RED = 0xFFE02020;

    /** How damaged the tape looks: 0 = clean, 1 = like the Backrooms videos, 2 = very rough. */
    static final float VHS = 1.0f;

    public static void render(GuiGraphics g, DeltaTracker dt) {
        if (!BackroomsCamClient.cameraUp) return;
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), h = g.guiHeight();

        // old-tape yellow tint + scanlines
        g.fill(0, 0, w, h, 0x1A8C7A2A);
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x16000000);

        if (VHS > 0f) vhs(g, w, h);

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
            g.drawString(mc.font, BackroomsCamClient.audioLabel, m + 8, m + 38, WHITE);
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

    /** Worn-VHS look: flicker, static, glitch lines, a rolling tracking band and head-switching noise at the bottom. */
    private static void vhs(GuiGraphics g, int w, int h) {
        long now = System.currentTimeMillis();
        Random rnd = new Random((now / 66) * 7919L); // new noise ~15 times a second

        // brightness flicker (now and then a harder pulse)
        int flick = rnd.nextInt(100) < 10 ? 25 + rnd.nextInt(35) : rnd.nextInt(10);
        g.fill(0, 0, w, h, ((int) (flick * VHS)) << 24);

        // static speckles
        int speckles = (int) (120 * VHS);
        for (int i = 0; i < speckles; i++) {
            int x = rnd.nextInt(w), y = rnd.nextInt(h), sw = 1 + rnd.nextInt(3);
            int a = 40 + rnd.nextInt(70);
            g.fill(x, y, Math.min(w, x + sw), y + 1, (a << 24) | (rnd.nextBoolean() ? 0xFFFFFF : 0xB8B8B8));
        }

        // glitch line bursts: a few bright lines with red/cyan fringes, in short bursts every ~1.5 s
        long window = now / 1500;
        Random br = new Random(window * 31337L);
        if (now % 1500 < 350 && br.nextInt(100) < 70) {
            int lines = 2 + br.nextInt(4);
            for (int i = 0; i < lines; i++) {
                int y = br.nextInt(h), thick = 1 + br.nextInt(3);
                int x0 = br.nextInt(Math.max(1, w / 2)), x1 = Math.min(w, x0 + w / 3 + br.nextInt(Math.max(1, w / 2)));
                g.fill(x0, y, x1, y + thick, 0x77FFFFFF);
                g.fill(Math.min(w, x0 + 3), y + thick, Math.min(w, x1 + 3), y + thick + 1, 0x5500FFFF);
                g.fill(Math.max(0, x0 - 3), y - 1, Math.max(0, x1 - 3), y, 0x55FF0000);
            }
        }

        // rolling tracking band that drifts up the screen every ~7 s
        int period = 7000;
        float p = (now % period) / (float) period;
        int bandH = 14 + (int) (10 * VHS);
        int bandY = (int) ((1f - p) * (h + bandH * 2)) - bandH;
        g.fill(0, bandY, w, bandY + bandH, 0x22FFFFFF);
        for (int y = bandY; y < bandY + bandH; y += 2) {
            noiseRow(g, rnd, w, y, 8, 40, 0.35f, 70);
        }

        // head-switching noise strip along the bottom edge
        int strip = 6 + (int) (4 * VHS);
        g.fill(0, h - strip, w, h, 0x55000000);
        for (int y = h - strip; y < h; y++) {
            noiseRow(g, rnd, w, y, 4, 34, 0.65f, 130);
        }
    }

    /** One row of random white dashes. */
    private static void noiseRow(GuiGraphics g, Random rnd, int w, int y, int minLen, int maxLen, float density, int maxAlpha) {
        int x = rnd.nextInt(12);
        while (x < w) {
            int len = minLen + rnd.nextInt(maxLen - minLen + 1);
            if (rnd.nextFloat() < density) {
                int a = 50 + rnd.nextInt(Math.max(1, maxAlpha - 50));
                g.fill(x, y, Math.min(w, x + len), y + 1, (a << 24) | 0xFFFFFF);
            }
            x += len + rnd.nextInt(10);
        }
    }

    private static void corner(GuiGraphics g, int x, int y, int len, int t, int dx, int dy) {
        g.fill(Math.min(x, x + dx * len), Math.min(y, y + dy * t), Math.max(x, x + dx * len), Math.max(y, y + dy * t), WHITE);
        g.fill(Math.min(x, x + dx * t), Math.min(y, y + dy * len), Math.max(x, x + dx * t), Math.max(y, y + dy * len), WHITE);
    }
}
