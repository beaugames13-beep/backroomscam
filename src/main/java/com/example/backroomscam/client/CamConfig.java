package com.example.backroomscam.client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings saved in config/backroomscam.properties. Change them in game with the /camcorder command. */
public final class CamConfig {
    public static final String DEFAULT_GAME_DEVICE = "Stereo Mix|CABLE Output|Loopback";

    public static String micDevice = "";                 // "" = the system's default microphone
    public static String gameDevice = DEFAULT_GAME_DEVICE; // device that hears game sound + voice chat
    public static boolean recordMic = true;
    public static boolean recordGame = true;
    public static float micGain = 1.0f;
    public static float gameGain = 1.0f;

    private static Path file;

    private CamConfig() {}

    public static void load(Path configDir) {
        file = configDir.resolve("backroomscam.properties");
        if (!Files.exists(file)) {
            save();
            return;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        micDevice = p.getProperty("micDevice", micDevice).trim();
        gameDevice = p.getProperty("gameDevice", gameDevice).trim();
        recordMic = Boolean.parseBoolean(p.getProperty("recordMic", String.valueOf(recordMic)).trim());
        recordGame = Boolean.parseBoolean(p.getProperty("recordGame", String.valueOf(recordGame)).trim());
        micGain = parseGain(p.getProperty("micGain"), micGain);
        gameGain = parseGain(p.getProperty("gameGain"), gameGain);
    }

    public static void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("micDevice", micDevice);
        p.setProperty("gameDevice", gameDevice);
        p.setProperty("recordMic", String.valueOf(recordMic));
        p.setProperty("recordGame", String.valueOf(recordGame));
        p.setProperty("micGain", String.valueOf(micGain));
        p.setProperty("gameGain", String.valueOf(gameGain));
        try (Writer out = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            p.store(out, "Backrooms Camcorder. micDevice/gameDevice = part of an audio input's name (several allowed, separated by |). Easiest: use /camcorder in game.");
        } catch (IOException ignored) {
        }
    }

    /** True if the device name contains any of the |-separated names in hint. */
    public static boolean matches(String deviceName, String hint) {
        if (hint == null || hint.isBlank()) return false;
        for (String part : hint.split("\\|")) {
            String h = part.trim().toLowerCase();
            if (!h.isEmpty() && deviceName.toLowerCase().contains(h)) return true;
        }
        return false;
    }

    private static float parseGain(String s, float fallback) {
        try {
            float v = Float.parseFloat(s.trim());
            return Math.max(0f, Math.min(3f, v));
        } catch (Exception e) {
            return fallback;
        }
    }
}
