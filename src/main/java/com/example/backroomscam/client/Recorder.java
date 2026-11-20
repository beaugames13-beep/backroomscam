package com.example.backroomscam.client;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Recording pipeline: frames are converted + JPEG-encoded on several worker threads,
 * then one writer thread puts them (in order) into the AVI together with the audio.
 * Video keeps real-time speed: if capture stalls, the last frame is repeated instead of
 * dropped, so video and audio stay in sync.
 */
public class Recorder {
    public record Frame(int[] rgb, int w, int h) {}

    public interface AudioSource {
        byte[] poll();   // next chunk of PCM or null
        void stop();
    }

    private record Encoded(byte[] jpg, int w, int h) {}
    private record Job(Future<Encoded> result, int repeat) {}
    private static final Job END = new Job(null, 0);

    private final Path file;
    private final int fps;
    private final AudioSource audio;
    private final AviWriter avi;
    private final long startMs = System.currentTimeMillis();
    private final ExecutorService pool;
    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final Thread writer;
    private long reserved = 0;
    private volatile Consumer<String> onDone;

    public Recorder(Path file, int fps, AudioSource audio, int audioRate) {
        this.file = file;
        this.fps = fps;
        this.audio = audio;
        this.avi = new AviWriter(file, fps, audio != null ? audioRate : 0);
        int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "camcorder-encoder");
            t.setDaemon(true);
            return t;
        });
        this.writer = new Thread(this::writeLoop, "camcorder-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public boolean hasAudio() { return audio != null; }
    public int pending() { return pending.get(); }

    /** How many video frames are owed right now (>= 1 means "capture a frame"). Reserves them. */
    public int reserve() {
        long elapsed = System.currentTimeMillis() - startMs;
        long shouldHave = elapsed * fps / 1000 + 1;
        int due = (int) Math.min(shouldHave - reserved, fps * 2L);
        if (due <= 0) return 0;
        reserved += due;
        pending.incrementAndGet();
        return due;
    }

    /** Hand over a reserved frame. The converter runs on a worker thread. Returns false if recording already ended. */
    public boolean submit(Callable<Frame> converter, int repeat) {
        try {
            Future<Encoded> f = pool.submit(() -> {
                try {
                    Frame fr = converter.call();
                    return new Encoded(Jpeg.encode(fr.rgb(), fr.w(), fr.h(), 0.8f), fr.w(), fr.h());
                } finally {
                    pending.decrementAndGet();
                }
            });
            queue.add(new Job(f, repeat));
            return true;
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            return false;
        }
    }

    /** Stop recording. Remaining frames are written, then onDone gets a status message (on the writer thread). */
    public void finish(Consumer<String> onDone) {
        this.onDone = onDone;
        queue.add(END);
    }

    private void drainAudio() throws java.io.IOException {
        if (audio == null || !avi.isOpen()) return;
        byte[] c;
        while ((c = audio.poll()) != null) avi.addAudio(c);
    }

    private void writeLoop() {
        String msg;
        try {
            Encoded last = null;
            while (true) {
                Job j = queue.take();
                if (j == END) break;
                try {
                    last = j.result().get();
                } catch (ExecutionException ignored) {
                    // frame failed to convert: repeat the previous one to keep timing
                }
                if (last == null) continue;
                if (!avi.isOpen()) avi.open(last.w(), last.h());
                drainAudio();
                for (int i = 0; i < j.repeat(); i++) avi.addVideo(last.jpg());
            }
            if (audio != null) audio.stop();
            drainAudio();
            int n = avi.frameCount();
            avi.close();
            msg = n > 0
                    ? String.format("Saved video (%.1fs): %s", n / (double) fps, file)
                    : "Camcorder: recording was empty, nothing saved";
        } catch (Exception e) {
            msg = "Camcorder: failed to save video (" + e + ")";
            try { avi.close(); } catch (Exception ignored) { }
        } finally {
            pool.shutdownNow();
        }
        Consumer<String> cb = onDone;
        if (cb != null) cb.accept(msg);
    }
}
