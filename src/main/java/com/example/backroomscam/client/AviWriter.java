package com.example.backroomscam.client;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 * Tiny pure-Java video writer: Motion-JPEG inside an AVI container.
 * No libraries or ffmpeg needed; plays in VLC, Windows Media Player, most editors.
 * Frames are added as PNG bytes. The file is created when the first frame arrives
 * (that frame sets the video size).
 */
public class AviWriter implements Closeable {
    private static final int HEADER_SIZE = 224;
    private static final int MOVI_POS = 220; // position of the 'movi' fourcc

    private final Path path;
    private final int fps;
    private final ImageWriter jpegWriter = ImageIO.getImageWritersByFormatName("jpg").next();
    private final List<int[]> index = new ArrayList<>();
    private RandomAccessFile file;
    private int w, h, frames, maxChunk;

    public AviWriter(Path path, int fps) {
        this.path = path;
        this.fps = fps;
    }

    public synchronized int frameCount() {
        return frames;
    }

    public synchronized void addPng(byte[] png) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        if (src == null) return;
        BufferedImage rgb = toOpaqueRgb(src);
        if (file == null) {
            w = rgb.getWidth();
            h = rgb.getHeight();
            open();
        }
        if (rgb.getWidth() != w || rgb.getHeight() != h) { // window was resized: rescale to first frame's size
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(rgb, 0, 0, w, h, null);
            g.dispose();
            rgb = scaled;
        }
        writeChunk(toJpeg(rgb));
    }

    @Override
    public synchronized void close() throws IOException {
        jpegWriter.dispose();
        if (file == null) return;
        long moviEnd = file.getFilePointer();

        ByteBuffer idx = le(8 + 16 * index.size());
        fourcc(idx, "idx1");
        idx.putInt(16 * index.size());
        for (int[] e : index) {
            fourcc(idx, "00dc");
            idx.putInt(0x10);
            idx.putInt(e[0]);
            idx.putInt(e[1]);
        }
        file.write(idx.array());

        long len = file.length();
        patch(4, (int) (len - 8));
        patch(36, maxChunk * fps);
        patch(48, frames);
        patch(60, maxChunk);
        patch(140, frames);
        patch(144, maxChunk);
        patch(216, (int) (moviEnd - MOVI_POS));
        file.close();
        file = null;
    }

    private void open() throws IOException {
        file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(0);
        ByteBuffer b = le(HEADER_SIZE);
        fourcc(b, "RIFF"); b.putInt(0); fourcc(b, "AVI ");
        fourcc(b, "LIST"); b.putInt(192); fourcc(b, "hdrl");
        fourcc(b, "avih"); b.putInt(56);
        b.putInt(1_000_000 / fps); b.putInt(0); b.putInt(0); b.putInt(0x10); // usec/frame, maxBytes/s, pad, HASINDEX
        b.putInt(0); b.putInt(0); b.putInt(1); b.putInt(0);                  // frames, initial, streams, bufsize
        b.putInt(w); b.putInt(h);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        fourcc(b, "LIST"); b.putInt(116); fourcc(b, "strl");
        fourcc(b, "strh"); b.putInt(56);
        fourcc(b, "vids"); fourcc(b, "MJPG");
        b.putInt(0); b.putShort((short) 0); b.putShort((short) 0); b.putInt(0);
        b.putInt(1); b.putInt(fps); b.putInt(0); b.putInt(0);               // scale, rate, start, length
        b.putInt(0); b.putInt(-1); b.putInt(0);                              // bufsize, quality, sampleSize
        b.putShort((short) 0); b.putShort((short) 0); b.putShort((short) w); b.putShort((short) h);
        fourcc(b, "strf"); b.putInt(40);
        b.putInt(40); b.putInt(w); b.putInt(h); b.putShort((short) 1); b.putShort((short) 24);
        fourcc(b, "MJPG"); b.putInt(w * h * 3);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        fourcc(b, "LIST"); b.putInt(0); fourcc(b, "movi");
        file.write(b.array());
    }

    private void writeChunk(byte[] jpg) throws IOException {
        long pos = file.getFilePointer();
        ByteBuffer hdr = le(8);
        fourcc(hdr, "00dc");
        hdr.putInt(jpg.length);
        file.write(hdr.array());
        file.write(jpg);
        if ((jpg.length & 1) == 1) file.write(0);
        index.add(new int[]{(int) (pos - MOVI_POS), jpg.length});
        frames++;
        maxChunk = Math.max(maxChunk, jpg.length);
    }

    private byte[] toJpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageWriteParam p = jpegWriter.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.8f);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
            jpegWriter.setOutput(out);
            jpegWriter.write(null, new IIOImage(img, null, null), p);
        }
        return bos.toByteArray();
    }

    /** Drops the alpha channel (screenshots can have alpha 0) so JPEG output isn't black. */
    private static BufferedImage toOpaqueRgb(BufferedImage src) {
        int sw = src.getWidth(), sh = src.getHeight();
        int[] px = src.getRGB(0, 0, sw, sh, null, 0, sw);
        for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
        BufferedImage out = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, sw, sh, px, 0, sw);
        return out;
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
