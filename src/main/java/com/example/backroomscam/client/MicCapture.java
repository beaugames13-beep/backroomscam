package com.example.backroomscam.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Records from any audio INPUT device (microphone, "Stereo Mix", a virtual cable...) on a background thread
 * and delivers 44.1 kHz, 16-bit, mono PCM no matter what the device natively gives us.
 */
public class MicCapture implements Recorder.AudioSource {
    public static final int RATE = 44100;
    private static final int[] RATES = {44100, 48000};
    private static final int[] CHANNELS = {1, 2};

    private final TargetDataLine line;
    private final Converter converter;
    private final int frameBytes;
    private final ConcurrentLinkedQueue<byte[]> chunks = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    private MicCapture(TargetDataLine line, int rate, int channels) {
        this.line = line;
        this.converter = new Converter(rate, channels);
        this.frameBytes = 2 * channels;
        this.thread = new Thread(this::run, "camcorder-audio");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * @param deviceHint part of the input device's name (case-insensitive), e.g. "Stereo Mix". Several names can be separated by "|". Empty = default input.
     * @param allowDefault if the named device isn't found, fall back to the default microphone
     * @return null if nothing usable was found
     */
    public static MicCapture open(String deviceHint, boolean allowDefault) {
        for (int rate : RATES) {
            for (int ch : CHANNELS) {
                try {
                    AudioFormat fmt = new AudioFormat(rate, 16, ch, true, false);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                    TargetDataLine line = findLine(info, deviceHint, allowDefault);
                    if (line == null) continue;
                    line.open(fmt, rate * ch * 2 / 2); // ~500 ms buffer
                    line.start();
                    return new MicCapture(line, rate, ch);
                } catch (LineUnavailableException | RuntimeException e) {
                    // try the next format
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    private static TargetDataLine findLine(DataLine.Info info, String hint, boolean allowDefault) throws LineUnavailableException {
        boolean named = hint != null && !hint.isBlank();
        if (named) {
            // several names can be given, separated by "|": the first one that exists is used
            for (String part : hint.split("\\|")) {
                String h = part.trim().toLowerCase();
                if (h.isEmpty()) continue;
                for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                    if (!mi.getName().toLowerCase().contains(h)) continue;
                    Mixer m = AudioSystem.getMixer(mi);
                    if (m.isLineSupported(info)) return (TargetDataLine) m.getLine(info);
                }
            }
            if (!allowDefault) return null;
        }
        return AudioSystem.isLineSupported(info) ? (TargetDataLine) AudioSystem.getLine(info) : null;
    }

    /** Loudest sample (0..32768) in a chunk of 16-bit little-endian PCM. */
    static int peak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            peak = Math.max(peak, Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8))));
        }
        return peak;
    }

    /** Names of audio input devices Java can see (so you know what to put in the hint). */
    public static List<String> listInputs() {
        List<String> names = new ArrayList<>();
        try {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                Mixer m = AudioSystem.getMixer(mi);
                if (m.getTargetLineInfo().length > 0 && !mi.getName().startsWith("Port ")) names.add(mi.getName());
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private void run() {
        byte[] buf = new byte[line.getFormat().getFrameSize() * (line.getFormat().getSampleRate() > 0 ? (int) line.getFormat().getSampleRate() / 50 : 882)];
        while (running) {
            int n = line.read(buf, 0, buf.length);
            if (n > 0) chunks.add(converter.process(buf, n - (n % frameBytes)));
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

    /** Raw device PCM (any rate, 1 or 2 channels) -> 44.1 kHz mono 16-bit little-endian. */
    static final class Converter {
        private final int rate, channels;
        private short prev;
        private double t;

        Converter(int rate, int channels) {
            this.rate = rate;
            this.channels = channels;
        }

        byte[] process(byte[] in, int n) {
            int frames = n / (2 * channels);
            short[] mono = new short[frames];
            for (int f = 0; f < frames; f++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int i = (f * channels + c) * 2;
                    sum += (short) ((in[i] & 0xFF) | (in[i + 1] << 8));
                }
                mono[f] = (short) (sum / channels);
            }
            if (rate == RATE) return pack(mono, frames);

            short[] arr = new short[frames + 1];
            arr[0] = prev;
            System.arraycopy(mono, 0, arr, 1, frames);
            int last = arr.length - 1;
            double step = rate / (double) RATE;
            short[] out = new short[(int) ((last - t) / step) + 2];
            int o = 0;
            while (t < last) {
                int i = (int) t;
                double fr = t - i;
                out[o++] = (short) Math.round(arr[i] * (1 - fr) + arr[i + 1] * fr);
                t += step;
            }
            t -= last;
            prev = arr[last];
            return pack(out, o);
        }

        private static byte[] pack(short[] s, int count) {
            byte[] b = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                b[2 * i] = (byte) s[i];
                b[2 * i + 1] = (byte) (s[i] >> 8);
            }
            return b;
        }
    }
}
