package com.example.backroomscam.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/** /camcorder ... : pick the microphone and the "game sound" device without editing files. */
final class CamCommands {
    private CamCommands() {}

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("camcorder")
                .executes(c -> status(c.getSource()))
                .then(ClientCommandManager.literal("status").executes(c -> status(c.getSource())))
                .then(ClientCommandManager.literal("devices").executes(c -> devices(c.getSource())))
                .then(ClientCommandManager.literal("mic")
                        .then(ClientCommandManager.argument("which", StringArgumentType.greedyString())
                                .executes(c -> setMic(c.getSource(), StringArgumentType.getString(c, "which")))))
                .then(ClientCommandManager.literal("game")
                        .then(ClientCommandManager.argument("which", StringArgumentType.greedyString())
                                .executes(c -> setGame(c.getSource(), StringArgumentType.getString(c, "which")))))
                .then(ClientCommandManager.literal("micgain")
                        .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(0, 3))
                                .executes(c -> {
                                    CamConfig.micGain = (float) DoubleArgumentType.getDouble(c, "value");
                                    CamConfig.save();
                                    say(c.getSource(), "Mic volume set to " + CamConfig.micGain);
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("gamegain")
                        .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(0, 3))
                                .executes(c -> {
                                    CamConfig.gameGain = (float) DoubleArgumentType.getDouble(c, "value");
                                    CamConfig.save();
                                    say(c.getSource(), "Game sound volume set to " + CamConfig.gameGain);
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("test")
                        .then(ClientCommandManager.literal("mic").executes(c -> test(c.getSource(), false)))
                        .then(ClientCommandManager.literal("game").executes(c -> test(c.getSource(), true)))));
    }

    private static void say(FabricClientCommandSource s, String msg) {
        s.sendFeedback(Component.literal(msg));
    }

    private static int status(FabricClientCommandSource s) {
        say(s, "Camcorder audio settings:");
        say(s, " Mic: " + (!CamConfig.recordMic ? "OFF" : CamConfig.micDevice.isEmpty() ? "default microphone" : CamConfig.micDevice)
                + "  (volume " + CamConfig.micGain + ")");
        say(s, " Game sound + voice chat: " + (!CamConfig.recordGame ? "OFF" : CamConfig.gameDevice)
                + "  (volume " + CamConfig.gameGain + ")");
        say(s, "Commands: /camcorder devices | mic <number> | game <number> | test mic | test game | micgain | gamegain");
        return 1;
    }

    private static int devices(FabricClientCommandSource s) {
        List<String> names = MicCapture.listInputs();
        if (names.isEmpty()) {
            say(s, "No audio inputs found.");
            return 0;
        }
        say(s, "Audio inputs (use the number with /camcorder mic <number> or /camcorder game <number>):");
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            String tag = "";
            if (CamConfig.recordMic && CamConfig.matches(n, CamConfig.micDevice)) tag += "  [MIC]";
            if (CamConfig.recordGame && CamConfig.matches(n, CamConfig.gameDevice)) tag += "  [GAME]";
            say(s, " " + (i + 1) + ": " + n + tag);
        }
        return 1;
    }

    /** "3" -> the name of device 3; anything else is used as a name. Returns null if the number is out of range. */
    private static String resolve(FabricClientCommandSource s, String arg) {
        String a = arg.trim();
        if (a.matches("\\d+")) {
            List<String> names = MicCapture.listInputs();
            int idx = Integer.parseInt(a) - 1;
            if (idx < 0 || idx >= names.size()) {
                say(s, "No device with number " + a + ". Run /camcorder devices to see the list.");
                return null;
            }
            return names.get(idx);
        }
        return a;
    }

    private static int setMic(FabricClientCommandSource s, String arg) {
        String a = arg.trim();
        if (a.equalsIgnoreCase("off")) {
            CamConfig.recordMic = false;
            CamConfig.save();
            say(s, "Microphone recording is OFF.");
            return 1;
        }
        if (a.equalsIgnoreCase("default")) {
            CamConfig.recordMic = true;
            CamConfig.micDevice = "";
            CamConfig.save();
            say(s, "Microphone: using the system default microphone.");
            return 1;
        }
        String name = resolve(s, a);
        if (name == null) return 0;
        CamConfig.recordMic = true;
        CamConfig.micDevice = name;
        CamConfig.save();
        say(s, "Microphone set to: " + name + "   (try it with /camcorder test mic)");
        return 1;
    }

    private static int setGame(FabricClientCommandSource s, String arg) {
        String a = arg.trim();
        if (a.equalsIgnoreCase("off")) {
            CamConfig.recordGame = false;
            CamConfig.save();
            say(s, "Game sound recording is OFF.");
            return 1;
        }
        if (a.equalsIgnoreCase("auto")) {
            CamConfig.recordGame = true;
            CamConfig.gameDevice = CamConfig.DEFAULT_GAME_DEVICE;
            CamConfig.save();
            say(s, "Game sound: automatic (" + CamConfig.DEFAULT_GAME_DEVICE + ").");
            return 1;
        }
        String name = resolve(s, a);
        if (name == null) return 0;
        CamConfig.recordGame = true;
        CamConfig.gameDevice = name;
        CamConfig.save();
        say(s, "Game sound device set to: " + name + "   (play a sound, then /camcorder test game)");
        return 1;
    }

    /** Listens to the chosen device for 2 seconds and reports how loud it was. */
    private static int test(FabricClientCommandSource s, boolean game) {
        String hint = game ? CamConfig.gameDevice : CamConfig.micDevice;
        say(s, "Listening for 2 seconds... " + (game ? "(make some game sound or talk in voice chat)" : "(say something)"));
        Minecraft mc = Minecraft.getInstance();
        Thread t = new Thread(() -> {
            String result;
            MicCapture m = MicCapture.open(hint, !game);
            if (m == null) {
                result = "Couldn't open that device. Run /camcorder devices and pick one.";
            } else {
                int peak = 0;
                long end = System.currentTimeMillis() + 2000;
                try {
                    while (System.currentTimeMillis() < end) {
                        byte[] c;
                        while ((c = m.poll()) != null) peak = Math.max(peak, MicCapture.peak(c));
                        Thread.sleep(20);
                    }
                } catch (InterruptedException ignored) {
                }
                m.stop();
                int pct = peak * 100 / 32768;
                result = pct < 1
                        ? "Heard nothing (level 0%). Wrong device, or nothing was playing/said."
                        : "Heard sound, level " + pct + "%. " + (pct > 95 ? "That's very loud, lower the volume." : "This device works.");
            }
            final String msg = result;
            mc.execute(() -> say(s, msg));
        }, "camcorder-test");
        t.setDaemon(true);
        t.start();
        return 1;
    }
}
