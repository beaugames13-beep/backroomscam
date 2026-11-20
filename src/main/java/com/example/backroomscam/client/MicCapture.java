package com.example.backroomscam.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Records 16-bit mono PCM from a microphone (or any input device) on a background thread. */
public class MicCapture implements Recorder.AudioSource {
    public static final int RATE = 44100;

    private final TargetDataLine line;
    private final ConcurrentLinkedQueue<byte[]> chunks = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    private MicCapture(TargetDataLine line) {
        this.line = line;
        this.thread = new Thread(this::run, "camcorder-mic");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** @param deviceHint part of an input device's name (e.g. "Stereo Mix"); empty = default input. Returns null if none works. */
    public static MicCapture tryStart(String deviceHint) {
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            TargetDataLine line = null;

            if (deviceHint != null && !deviceHint.isBlank()) {
                String hint = deviceHint.toLowerCase();
                for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                    if (!mi.getName().toLowerCase().contains(hint)) continue;
                    Mixer m = AudioSystem.getMixer(mi);
                    if (m.isLineSupported(info)) {
                        line = (TargetDataLine) m.getLine(info);
                        break;
                    }
                }
            }
            if (line == null) {
                if (!AudioSystem.isLineSupported(info)) return null;
                line = (TargetDataLine) AudioSystem.getLine(info);
            }
            line.open(fmt, RATE / 2); // ~250 ms internal buffer
            line.start();
            return new MicCapture(line);
        } catch (Throwable t) {
            return null;
        }
    }

    private void run() {
        byte[] buf = new byte[RATE / 50 * 2]; // 20 ms
        while (running) {
            int n = line.read(buf, 0, buf.length);
            if (n > 0) chunks.add(Arrays.copyOf(buf, n - (n % 2)));
            else if (n < 0) break;
        }
    }

    @Override
    public byte[] poll() {
        return chunks.poll();
    }

    @Override
    public void stop() {
        running = false;
        try {
            line.stop();
            line.close();
            thread.join(300);
        } catch (Exception ignored) {
        }
    }
}
