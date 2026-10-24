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

import org.junit.jupiter.api.Test;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.PageFetcher;

class GuaziSourceAdapterTest {

    /** Serves the saved pages instead of the network and remembers what was asked for. */
    private static final class SavedPages extends PageFetcher {
        final List<URI> requested = new ArrayList<>();

        SavedPages() {
            super(null);
        }

        @Override
        public String fetchHtml(URI url) {
            requested.add(url);
            String id = GuaziPageParser.productId(url.toString());
            try (InputStream in = getClass().getResourceAsStream("/pages/guazi-" + id + ".html")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private final SavedPages pages = new SavedPages();
    private final GuaziSourceAdapter adapter = new GuaziSourceAdapter(pages);

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
}
