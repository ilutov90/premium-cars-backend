package com.premiumcars.importing.source.autowini;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.premiumcars.importing.source.autowini.AutowiniPageParser.ParsedPage;

import com.premiumcars.importing.browser.PageRenderer.CapturedImage;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageDownloader.DownloadedImage;

class AutowiniSourceAdapterTest {

    private static final String DIR = "https://imagebox.autowini.com/upload/U1/car/CI1/";

    @Test
    void photoKeyIgnoresSizeSuffixAndQuery() {
        assertThat(AutowiniSourceAdapter.photoKey(DIR + "5d6db85f-3c1b_1024.jpg?v=2"))
                .isEqualTo(AutowiniSourceAdapter.photoKey(DIR + "5d6db85f-3c1b_320.jpg"))
                .isEqualTo(AutowiniSourceAdapter.photoKey(DIR + "5d6db85f-3c1b.jpeg"))
                .isEqualTo("5d6db85f-3c1b");
    }

    @Test
    void galleryPhotosFillTheEmptySlotsOncePerPhotoLargestFirst() {
        byte[] big = jpeg(800, 600);
        byte[] small = jpeg(320, 240);
        byte[] tiny = jpeg(40, 40); // an icon: below the minimum size
        Map<Integer, DownloadedImage> out = new HashMap<>();
        out.put(1, ImageDownloader.accept(big).orElseThrow()); // slot 1 already downloaded

        AutowiniSourceAdapter.fillFromPage(out, 4, List.of(
                new CapturedImage(DIR + "p1_320.jpg", small),
                new CapturedImage(DIR + "p1_1024.jpg", big),
                new CapturedImage("https://www.autowini.com/upload/icon.png", tiny),
                new CapturedImage(DIR + "p2_1024.jpg", big)));

        assertThat(out).containsOnlyKeys(0, 1, 2);
        assertThat(out.get(0).bytes()).isEqualTo(big); // p1, the 1024 version
        assertThat(out.get(2).bytes()).isEqualTo(big); // p2
    }

    private static final ParsedPage MOBILE = new ParsedPage("IC5341711", Map.of("item_no", "IC5341711"), null, null,
            null, List.of(DIR + "m1_1024.jpg"), List.of());

    @Test
    void theApiPriceGoesIntoTheMobilePage() {
        ParsedPage page = AutowiniSourceAdapter.withPrice(MOBILE,
                new AutowiniClient.ApiPrices(new java.math.BigDecimal("16377"), null));
        assertThat(page.price()).isEqualByComparingTo("16377");
        assertThat(page.currency()).isEqualTo("USD");
        assertThat(page.discount()).isNull();
        assertThat(page.images()).isEqualTo(MOBILE.images());
        assertThat(page.fields()).isEqualTo(MOBILE.fields());

        ParsedPage event = AutowiniSourceAdapter.withPrice(MOBILE,
                new AutowiniClient.ApiPrices(new java.math.BigDecimal("16377"), new java.math.BigDecimal("15877")));
        assertThat(event.price()).isEqualByComparingTo("16377");
        assertThat(event.discount()).isEqualByComparingTo("500");

        assertThat(AutowiniSourceAdapter.withPrice(MOBILE, null)).isSameAs(MOBILE);
    }

    /** Random pixels, so the JPEG is large like a real photo. */
    private static byte[] jpeg(int w, int h) {
        try {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Random random = new Random(w * 31L + h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    image.setRGB(x, y, random.nextInt(0xffffff));
                }
            }
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
