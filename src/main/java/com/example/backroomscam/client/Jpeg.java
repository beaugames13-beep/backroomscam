package com.example.backroomscam.client;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Thread-safe RGB int[] -> JPEG bytes. */
final class Jpeg {
    private static final ThreadLocal<ImageWriter> WRITER =
            ThreadLocal.withInitial(() -> ImageIO.getImageWritersByFormatName("jpg").next());

    private Jpeg() {}

    static byte[] encode(int[] rgb, int w, int h, float quality) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        System.arraycopy(rgb, 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, w * h);

        ImageWriter writer = WRITER.get();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 4);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), p);
        }
        return bos.toByteArray();
    }
}
