package com.example.backroomscam.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mixes several 44.1 kHz mono PCM sources (e.g. microphone + game/voice-chat loopback) into one stream,
 * paced by the wall clock so the audio stays in sync with the video even if a device stalls.
 */
public class MixedAudio implements Recorder.AudioSource {
    private static final int RATE = MicCapture.RATE;
    private static final int LATENCY = RATE * 60 / 1000; // stay 60 ms behind real time so devices never run dry

    private static final class Buf {
        byte[] d = new byte[8192];
        int len;

        void add(byte[] c) {
            if (len + c.length > d.length) d = Arrays.copyOf(d, Math.max(d.length * 2, len + c.length));
            System.arraycopy(c, 0, d, len, c.length);
            len += c.length;
        }

        void drop(int n) {
            System.arraycopy(d, n, d, 0, len - n);
            len -= n;
        }
    }

    private final List<Recorder.AudioSource> sources;
    private final float[] gains;
    private final List<Buf> bufs = new ArrayList<>();
    private final long startNs = System.nanoTime();
    private long emitted = 0;
    private boolean stopped = false;

    public MixedAudio(List<Recorder.AudioSource> sources, float[] gains) {
        this.sources = sources;
        this.gains = gains;
        for (int i = 0; i < sources.size(); i++) bufs.add(new Buf());
    }

    private void pull() {
        for (int i = 0; i < sources.size(); i++) {
            byte[] c;
            while ((c = sources.get(i).poll()) != null) bufs.get(i).add(c);
        }
    }

    @Override
    public byte[] poll() {
        pull();
        int n;
        if (!stopped) {
            long due = (System.nanoTime() - startNs) * RATE / 1_000_000_000L - emitted - LATENCY;
            if (due < RATE / 100) return null;
            n = (int) Math.min(due, RATE);
        } else {
            int most = 0;
            for (Buf b : bufs) most = Math.max(most, b.len / 2);
            if (most == 0) return null;
            n = Math.min(most, RATE);
        }

        int[] acc = new int[n];
        for (int s = 0; s < bufs.size(); s++) {
            Buf b = bufs.get(s);
            int take = Math.min(n, b.len / 2);
            float g = gains[s];
            for (int i = 0; i < take; i++) {
                acc[i] += (int) (((short) ((b.d[2 * i] & 0xFF) | (b.d[2 * i + 1] << 8))) * g);
            }
            b.drop(take * 2);
            if (!stopped && b.len / 2 > RATE / 4) { // a device runs fast: skip ahead instead of drifting out of sync
                b.drop((b.len / 2 - RATE / 20) * 2);
            }
        }
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = Math.max(-32768, Math.min(32767, acc[i]));
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        emitted += n;
        return out;
    }

    @Override
    public void stop() {
        for (Recorder.AudioSource s : sources) s.stop();
        stopped = true;
    }
}
