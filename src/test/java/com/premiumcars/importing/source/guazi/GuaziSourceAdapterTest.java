package com.premiumcars.importing.source.guazi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.PageFetcher;
import com.premiumcars.importing.browser.PageRenderer;

class GuaziSourceAdapterTest {

    /** Serves the saved pages instead of the network and remembers what was asked for. */
    private static final class SavedPages extends PageFetcher {
        final List<URI> requested = new ArrayList<>();

        SavedPages() {
            super(null);
        }

        String answer; // what guazi sends instead of the listing, when set

        @Override
        public String fetchHtml(URI url) {
            requested.add(url);
            if (answer != null) {
                return answer;
            }
            String id = GuaziPageParser.productId(url.toString());
            try (InputStream in = getClass().getResourceAsStream("/pages/guazi-" + id + ".html")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The headless browser: off, or showing the given page. */
    private static final class FakeBrowser implements PageRenderer {
        boolean on;
        String html;
        final List<String> opened = new ArrayList<>();

        @Override
        public Optional<RenderedPage> render(String url, List<Pattern> clickTexts, Pattern responseHint) {
            throw new AssertionError("guazi waits for the listing (renderWhen)");
        }

        @Override
        public Optional<RenderedPage> renderWhen(String url, Pattern ready) {
            opened.add(url);
            return html != null && ready.matcher(html).find()
                    ? Optional.of(new RenderedPage(html, List.of())) : Optional.empty();
        }

        @Override
        public BrowserImages loadImages(String pageUrl, List<List<String>> candidates, List<BrowserCookie> cookies,
                                        Pattern pageImageFilter) {
            return BrowserImages.NONE;
        }

        @Override
        public boolean enabled() {
            return on;
        }
    }

    private final SavedPages pages = new SavedPages();
    private final FakeBrowser browser = new FakeBrowser();
    private final GuaziSourceAdapter adapter = new GuaziSourceAdapter(pages, browser);

    @Test
    void handlesEveryLanguageOfTheSite() {
        assertThat(adapter.supports(URI.create("https://en.guazi.com/products/x-68pfqu2abf.html"))).isTrue();
        assertThat(adapter.supports(URI.create("https://ru.guazi.com/products/x-68pfqu2abf.html"))).isTrue();
        assertThat(adapter.supports(URI.create("https://www.guazi.com.evil.example/x"))).isFalse();
        assertThat(adapter.supports(URI.create("https://global.autohome.com.cn/en/detail/1"))).isFalse();
    }

    @Test
    void importsTheEnglishPage() {
        ImportedCar car = adapter.importFrom(URI.create(
                "https://fr.guazi.com/products/byd-song-max-2021-15l-blue-103699km-at-6-seats-68pfqu2abf.html?ref=ad"));

        assertThat(pages.requested).containsExactly(URI.create(
                "https://en.guazi.com/products/byd-song-max-2021-15l-blue-103699km-at-6-seats-68pfqu2abf.html"));
        assertThat(car.sourceRef()).isEqualTo("68pfqu2abf");
        assertThat(car.sourceLocation()).isEqualTo("Shenyang, China");
        assertThat(car.price()).isEqualByComparingTo("7603");
        assertThat(car.currency()).isEqualTo("USD");
        assertThat(car.imageUrls()).hasSize(5);
        assertThat(car.warnings()).contains("guazi does not give the emission class (Euro): set it for mobile.bg.",
                "guazi inspection: grade A; issues: Exterior 6");
    }

    @Test
    void anElectricCarNeedsNoEmissionClass() {
        ImportedCar car = adapter.importFrom(URI.create(
                "https://en.guazi.com/products/tesla-model-y-2025-00l-gray-37900km-at-4wd-5-seats-bxrayk2hvc.html"));

        assertThat(car.specs().batteryHealthPct()).isEqualTo(91);
        assertThat(car.warnings()).noneMatch(w -> w.contains("emission class") || w.contains("not found"));
    }

    @Test
    void refusesOtherPagesOfTheSite() {
        assertThatThrownBy(() -> adapter.importFrom(URI.create("https://en.guazi.com/cars?brand=tesla")))
                .isInstanceOf(BadRequestException.class);
        assertThat(pages.requested).isEmpty();
    }

    private static final String BOT_CHECK = "<html><head><script>function a(a){var t='EO_Bot_Ssid=';}</script></head>"
            + "<body></body></html>";

    @Test
    void theCheckPageIsOpenedInTheBrowser() {
        URI tesla = URI.create(
                "https://en.guazi.com/products/tesla-model-y-2025-00l-gray-37900km-at-4wd-5-seats-bxrayk2hvc.html");
        String listing = pages.fetchHtml(tesla);
        pages.requested.clear();
        pages.answer = BOT_CHECK;
        browser.on = true;
        browser.html = listing;

        ImportedCar car = adapter.importFrom(tesla);

        assertThat(pages.requested).containsExactly(tesla); // one plain request, then the browser
        assertThat(browser.opened).containsExactly(tesla.toString());
        assertThat(car.sourceRef()).isEqualTo("bxrayk2hvc");
        assertThat(car.price()).isEqualByComparingTo("37899");
        assertThat(car.imageUrls()).hasSize(26);
    }

    @Test
    void theBrowserIsNotOpenedWhenThePlainPageHasTheListing() {
        browser.on = true;
        adapter.importFrom(URI.create("https://en.guazi.com/products/byd-song-max-2021-68pfqu2abf.html"));
        assertThat(browser.opened).isEmpty();
    }

    @Test
    void aCheckPageThatNeverGivesTheListing() {
        URI tesla = URI.create("https://en.guazi.com/products/x-bxrayk2hvc.html");
        pages.answer = BOT_CHECK;

        assertThatThrownBy(() -> adapter.importFrom(tesla)) // browser off
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Next.js data missing");

        browser.on = true; // browser on, but the check page stays
        assertThatThrownBy(() -> adapter.importFrom(tesla))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not even in the browser");
    }

    @Test
    void aSavedPageCanStillBeImported() {
        URI tesla = URI.create(
                "https://en.guazi.com/products/tesla-model-y-2025-00l-gray-37900km-at-4wd-5-seats-bxrayk2hvc.html");
        String saved = pages.fetchHtml(tesla);
        ImportedCar car = adapter.importFromHtml(tesla, saved);
        assertThat(car.sourceRef()).isEqualTo("bxrayk2hvc");
        assertThat(car.imageUrls()).hasSize(26);
    }

    @Test
    void aSavedPageMustBeTheLinkedListing() {
        URI byd = URI.create("https://en.guazi.com/products/byd-song-max-2021-68pfqu2abf.html");
        String tesla = pages.fetchHtml(URI.create("https://en.guazi.com/products/x-bxrayk2hvc.html"));

        assertThatThrownBy(() -> adapter.importFromHtml(byd, tesla))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("is listing bxrayk2hvc, not 68pfqu2abf");
        assertThatThrownBy(() -> adapter.importFromHtml(byd, BOT_CHECK))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not this guazi listing");
    }
}
