package com.premiumcars.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** Viber shows photos up to 1 MB: bigger JPEGs are scaled down, small ones are left alone. */
class SocialPublisherPhotoTest {

    @Test
    void bigPhotosAreScaledDownBelowTheLimit() throws IOException {
        byte[] big = noiseJpeg(2400, 1600);
        assertThat(big.length).isGreaterThan(SocialPublisher.VIBER_PHOTO_MAX_BYTES);

        byte[] fitted = SocialPublisher.fitWithin(big, SocialPublisher.VIBER_PHOTO_MAX_BYTES);

        assertThat(fitted.length).isLessThanOrEqualTo(SocialPublisher.VIBER_PHOTO_MAX_BYTES);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(fitted));
        assertThat(image.getWidth()).isLessThanOrEqualTo(1600);
        assertThat(image.getWidth() * 1600 / image.getHeight()).isBetween(2390, 2410); // aspect ratio kept
    }

    @Test
    void smallPhotosAreKeptAsTheyAre() throws IOException {
        byte[] small = noiseJpeg(200, 150);

        assertThat(SocialPublisher.fitWithin(small, SocialPublisher.VIBER_PHOTO_MAX_BYTES)).isSameAs(small);
    }

    /** Random pixels compress badly, so a few megapixels give a JPEG of several MB. */
    private static byte[] noiseJpeg(int width, int height) throws IOException {
        var random = new Random(42);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
