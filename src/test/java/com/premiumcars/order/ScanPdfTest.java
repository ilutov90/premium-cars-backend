package com.premiumcars.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import com.premiumcars.common.BadRequestException;

class ScanPdfTest {

    private static byte[] image(int w, int h, String format, boolean noise) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        if (noise) {
            Random r = new Random(1);
            for (int i = 0; i < 20000; i++) {
                img.setRGB(r.nextInt(w), r.nextInt(h), r.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void testCombine_ThreeImages_OnePagePerImageInOrderSizedToFit() throws Exception {
        byte[] small = image(400, 200, "png", false);
        byte[] bigLandscape = image(4000, 3000, "jpg", true);
        byte[] portrait = image(3000, 4000, "jpg", true);

        byte[] pdf = ScanPdf.combine(List.of(small, bigLandscape, portrait));

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            var p1 = doc.getPage(0).getMediaBox();
            var p2 = doc.getPage(1).getMediaBox();
            var p3 = doc.getPage(2).getMediaBox();
            assertThat(p1.getWidth()).isCloseTo(400 * 72f / 200, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(p1.getHeight()).isCloseTo(200 * 72f / 200, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(p2.getWidth()).isGreaterThan(p2.getHeight());
            assertThat(p2.getWidth()).isLessThanOrEqualTo(ScanPdf.LONG_SIDE * 72f / 200 + 1);
            assertThat(p2.getHeight()).isLessThanOrEqualTo(ScanPdf.SHORT_SIDE * 72f / 200 + 1);
            assertThat(p3.getHeight()).isGreaterThan(p3.getWidth());
            assertThat(p3.getHeight()).isLessThanOrEqualTo(ScanPdf.LONG_SIDE * 72f / 200 + 1);
        }
        assertThat(pdf.length).isLessThan(bigLandscape.length + portrait.length);
    }

    @Test
    void testCombine_PngWithAlpha_Converted() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        try (PDDocument doc = Loader.loadPDF(ScanPdf.combine(List.of(out.toByteArray())))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void testCombine_NotAnImage_BadRequest() {
        assertThatThrownBy(() -> ScanPdf.combine(List.of("not an image".getBytes())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Image 1");
    }

    @Test
    void testOrientation_ExifSix_ReadAndPageTurned() throws Exception {
        byte[] jpeg = image(600, 300, "jpg", false);
        byte[] exif = {(byte) 0xFF, (byte) 0xE1, 0, 30, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0};
        byte[] withExif = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, withExif, 0, 2);
        System.arraycopy(exif, 0, withExif, 2, exif.length);
        System.arraycopy(jpeg, 2, withExif, 2 + exif.length, jpeg.length - 2);

        assertThat(ScanPdf.orientation(withExif)).isEqualTo(6);
        assertThat(ScanPdf.orientation(jpeg)).isEqualTo(1);
        try (PDDocument doc = Loader.loadPDF(ScanPdf.combine(List.of(withExif)))) {
            assertThat(doc.getPage(0).getMediaBox().getHeight()).isGreaterThan(doc.getPage(0).getMediaBox().getWidth());
        }
    }
}
