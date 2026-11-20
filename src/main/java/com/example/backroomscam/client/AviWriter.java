package com.example.backroomscam.client;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal AVI muxer: Motion-JPEG video + optional 16-bit mono PCM audio.
 * Pure Java, no libraries. Call open(w,h) before the first addVideo/addAudio.
 */
public class AviWriter implements Closeable {
    private final Path path;
    private final int fps;
    private final int audioRate; // 0 = no audio track
    private final boolean hasAudio;
    private final List<int[]> index = new ArrayList<>(); // {isAudio, offset, size}
    private RandomAccessFile file;
    private int frames, maxVideoChunk, maxAudioChunk;
    private long audioBytes;

    public AviWriter(Path path, int fps, int audioRate) {
        this.path = path;
        this.fps = fps;
        this.audioRate = audioRate;
        this.hasAudio = audioRate > 0;
    }

    private int headerSize() { return hasAudio ? 324 : 224; }
    private int moviPos() { return headerSize() - 4; }

    public boolean isOpen() { return file != null; }
    public int frameCount() { return frames; }

    public void open(int w, int h) throws IOException {
        file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(0);
        ByteBuffer b = le(headerSize());
        fourcc(b, "RIFF"); b.putInt(0); fourcc(b, "AVI ");
        fourcc(b, "LIST"); b.putInt(hasAudio ? 292 : 192); fourcc(b, "hdrl");
        fourcc(b, "avih"); b.putInt(56);
        b.putInt(1_000_000 / fps); b.putInt(0); b.putInt(0); b.putInt(0x10);
        b.putInt(0); b.putInt(0); b.putInt(hasAudio ? 2 : 1); b.putInt(0);
        b.putInt(w); b.putInt(h);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        // video stream
        fourcc(b, "LIST"); b.putInt(116); fourcc(b, "strl");
        fourcc(b, "strh"); b.putInt(56);
        fourcc(b, "vids"); fourcc(b, "MJPG");
        b.putInt(0); b.putShort((short) 0); b.putShort((short) 0); b.putInt(0);
        b.putInt(1); b.putInt(fps); b.putInt(0); b.putInt(0);
        b.putInt(0); b.putInt(-1); b.putInt(0);
        b.putShort((short) 0); b.putShort((short) 0); b.putShort((short) w); b.putShort((short) h);
        fourcc(b, "strf"); b.putInt(40);
        b.putInt(40); b.putInt(w); b.putInt(h); b.putShort((short) 1); b.putShort((short) 24);
        fourcc(b, "MJPG"); b.putInt(w * h * 3);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        // audio stream
        if (hasAudio) {
            fourcc(b, "LIST"); b.putInt(92); fourcc(b, "strl");
            fourcc(b, "strh"); b.putInt(56);
            fourcc(b, "auds"); b.putInt(0);
            b.putInt(0); b.putShort((short) 0); b.putShort((short) 0); b.putInt(0);
            b.putInt(1); b.putInt(audioRate); b.putInt(0); b.putInt(0); // scale, rate, start, length(patched)
            b.putInt(0); b.putInt(-1); b.putInt(2);                      // bufsize, quality, sampleSize
            b.putShort((short) 0); b.putShort((short) 0); b.putShort((short) 0); b.putShort((short) 0);
            fourcc(b, "strf"); b.putInt(16);
            b.putShort((short) 1); b.putShort((short) 1);               // PCM, mono
            b.putInt(audioRate); b.putInt(audioRate * 2);
            b.putShort((short) 2); b.putShort((short) 16);
        }
        fourcc(b, "LIST"); b.putInt(0); fourcc(b, "movi");
        file.write(b.array());
    }

    public void addVideo(byte[] jpg) throws IOException {
        chunk("00dc", jpg, false);
        frames++;
        maxVideoChunk = Math.max(maxVideoChunk, jpg.length);
    }

    public void addAudio(byte[] pcm) throws IOException {
        if (!hasAudio) return;
        chunk("01wb", pcm, true);
        audioBytes += pcm.length;
        maxAudioChunk = Math.max(maxAudioChunk, pcm.length);
    }

    private void chunk(String id, byte[] data, boolean audio) throws IOException {
        long pos = file.getFilePointer();
        ByteBuffer hdr = le(8);
        fourcc(hdr, id);
        hdr.putInt(data.length);
        file.write(hdr.array());
        file.write(data);
        if ((data.length & 1) == 1) file.write(0);
        index.add(new int[]{audio ? 1 : 0, (int) (pos - moviPos()), data.length});
    }

    @Override
    public void close() throws IOException {
        if (file == null) return;
        long moviEnd = file.getFilePointer();
        ByteBuffer idx = le(8 + 16 * index.size());
        fourcc(idx, "idx1");
        idx.putInt(16 * index.size());
        for (int[] e : index) {
            fourcc(idx, e[0] == 1 ? "01wb" : "00dc");
            idx.putInt(0x10);
            idx.putInt(e[1]);
            idx.putInt(e[2]);
        }
        file.write(idx.array());

        long len = file.length();
        patch(4, (int) (len - 8));
        patch(36, maxVideoChunk * fps);
        patch(48, frames);
        patch(60, Math.max(maxVideoChunk, maxAudioChunk));
        patch(140, frames);
        patch(144, maxVideoChunk);
        if (hasAudio) {
            patch(264, (int) (audioBytes / 2));
            patch(268, maxAudioChunk);
        }
        patch(headerSize() - 8, (int) (moviEnd - moviPos()));
        file.close();
        file = null;
    }

    private void patch(long pos, int value) throws IOException {
        file.seek(pos);
        file.write(le(4).putInt(value).array());
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void fourcc(ByteBuffer b, String s) {
        b.put(s.getBytes(StandardCharsets.US_ASCII));
    }
}
