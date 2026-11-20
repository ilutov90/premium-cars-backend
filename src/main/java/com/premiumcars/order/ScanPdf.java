package com.premiumcars.order;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.premiumcars.common.BadRequestException;

/**
 * Combines photos (a phone's camera scan of a paper document) into one PDF: one page per image, in the given order.
 * A page is as big as its image after the image is reduced to fit A4 at {@value #DPI} dpi (never enlarged), and the
 * image is JPEG compressed, so a scan stays small. JPEG and PNG only (the JDK reads no WEBP); a JPEG's EXIF rotation
 * is applied.
 */
public final class ScanPdf {

    static final int DPI = 200;
    /** A4 at {@value #DPI} dpi, the long and the short side in pixels. */
    static final int LONG_SIDE = 2339;
    static final int SHORT_SIDE = 1654;
    static final float JPEG_QUALITY = 0.75f;
    private static final long MAX_PIXELS = 100_000_000L;

    private ScanPdf() {
    }

    /** @throws BadRequestException when an image cannot be read */
    public static byte[] combine(List<byte[]> images) {
        try (PDDocument doc = new PDDocument()) {
            int n = 0;
            for (byte[] bytes : images) {
                n++;
                BufferedImage image = fit(read(bytes, n));
                float w = image.getWidth() * 72f / DPI;
                float h = image.getHeight() * 72f / DPI;
                PDPage page = new PDPage(new PDRectangle(w, h));
                doc.addPage(page);
                PDImageXObject pdImage = JPEGFactory.createFromImage(doc, image, JPEG_QUALITY);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.drawImage(pdImage, 0, 0, w, h);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the PDF", e);
        }
    }

    private static BufferedImage read(byte[] bytes, int number) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new BadRequestException("Image " + number + " cannot be read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new BadRequestException("Image " + number + " is too large");
                }
                BufferedImage image = reader.read(0);
                return rotate(image, orientation(bytes));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BadRequestException b) {
                throw b;
            }
            throw new BadRequestException("Image " + number + " cannot be read");
        }
    }

    /** RGB, reduced (never enlarged) to fit A4 at {@value #DPI} dpi in its own orientation, on white. */
    static BufferedImage fit(BufferedImage src) {
        boolean landscape = src.getWidth() > src.getHeight();
        double maxW = landscape ? LONG_SIDE : SHORT_SIDE;
        double maxH = landscape ? SHORT_SIDE : LONG_SIDE;
        double scale = Math.min(1.0, Math.min(maxW / src.getWidth(), maxH / src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage rotate(BufferedImage src, int orientation) {
        int quarter = switch (orientation) {
            case 3 -> 2;
            case 6 -> 1;
            case 8 -> 3;
            default -> 0;
        };
        if (quarter == 0) {
            return src;
        }
        boolean swap = quarter != 2;
        int w = swap ? src.getHeight() : src.getWidth();
        int h = swap ? src.getWidth() : src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        AffineTransform t = new AffineTransform();
        t.translate(w / 2.0, h / 2.0);
        t.rotate(Math.PI / 2 * quarter);
        t.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
        var g = out.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The EXIF orientation (1..8) of a JPEG, 1 when there is none or the file is not a JPEG. */
    static int orientation(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 <= b.length && (b[pos] & 0xFF) == 0xFF) {
            int marker = b[pos + 1] & 0xFF;
            int len = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            if (marker == 0xDA || len < 2) {
                return 1;
            }
            if (marker == 0xE1 && len >= 16 && pos + 4 + 6 <= b.length && b[pos + 4] == 'E' && b[pos + 5] == 'x'
                    && b[pos + 6] == 'i' && b[pos + 7] == 'f') {
                return tiffOrientation(b, pos + 10, Math.min(b.length, pos + 2 + len));
            }
            pos += 2 + len;
        }
        return 1;
    }

    private static int tiffOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little = b[tiff] == 'I';
        int ifd = tiff + (int) u(b, tiff + 4, 4, little);
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int entries = (int) u(b, ifd, 2, little);
        for (int i = 0; i < entries; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > end) {
                return 1;
            }
            if (u(b, e, 2, little) == 0x0112) {
                int v = (int) u(b, e + 8, 2, little);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static long u(byte[] b, int pos, int size, boolean little) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            int x = b[pos + (little ? size - 1 - i : i)] & 0xFF;
            v = (v << 8) | x;
        }
        return v;
    }
}
