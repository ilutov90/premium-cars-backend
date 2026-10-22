package com.premiumcars.importing.source.autowini;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.browser.PageRenderer;
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

    /** A browser that returns the given desktop page (or nothing). */
    private static PageRenderer browser(String html, boolean enabled) {
        return new PageRenderer() {
            @Override
            public Optional<RenderedPage> render(String url, List<Pattern> clickTexts, Pattern responseHint) {
                return Optional.empty();
            }

            @Override
            public BrowserImages loadImages(String pageUrl, List<List<String>> candidates, List<BrowserCookie> cookies,
                                            Pattern pageImageFilter) {
                return BrowserImages.NONE;
            }

            @Override
            public Optional<String> pageHtml(String url, List<BrowserCookie> cookies) {
                return Optional.ofNullable(html);
            }

            @Override
            public boolean enabled() {
                return enabled;
            }
        };
    }

    private static String fixture() throws IOException {
        try (var in = AutowiniSourceAdapterTest.class.getResourceAsStream("/pages/autowini-IC5354760.html")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Not logged in: browserCookies() is empty, nothing goes over the network. */
    private static AutowiniClient client() {
        return new AutowiniClient(new AppProperties(null, null, null, null,
                new AppProperties.Importer("test-agent", 5, 10, new AppProperties.Browser(false, 5),
                        new AppProperties.Autowini("user", "pass")), null));
    }

    private static final ParsedPage MOBILE = new ParsedPage("IC5354760", Map.of("item_no", "IC5354760"), null, null,
            null, List.of(DIR + "m1_1024.jpg"));

    @Test
    void whenTheDesktopPageIsRefusedTheBrowserBringsItsPrice() throws IOException {
        var client = client();
        var adapter = new AutowiniSourceAdapter(client, browser(fixture(), true));

        ParsedPage page = adapter.desktopPageFromBrowser("https://www.autowini.com/Cars/IC5354760/cars-detail",
                "IC5354760", MOBILE);

        assertThat(page.price()).isEqualByComparingTo("19231");
        assertThat(page.currency()).isEqualTo("USD");
        assertThat(page.fields()).containsEntry("make", "Hyundai").containsEntry("km", "116,710Km (Actual)");
        assertThat(page.images()).containsExactly(DIR + "m1_1024.jpg"); // the mobile page's photos stay
    }

    @Test
    void withoutTheBrowserTheMobilePageStays() throws IOException {
        var client = client();
        assertThat(new AutowiniSourceAdapter(client, browser(fixture(), false))
                .desktopPageFromBrowser("u", "IC5354760", MOBILE)).isSameAs(MOBILE);
        // the browser was refused too, or got a page that is not the listing (login, error)
        assertThat(new AutowiniSourceAdapter(client, browser(null, true))
                .desktopPageFromBrowser("u", "IC5354760", MOBILE)).isSameAs(MOBILE);
        assertThat(new AutowiniSourceAdapter(client, browser("<html>Access denied</html>", true))
                .desktopPageFromBrowser("u", "IC5354760", MOBILE)).isSameAs(MOBILE);
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
