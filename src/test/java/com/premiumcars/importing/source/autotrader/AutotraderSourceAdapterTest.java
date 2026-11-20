package com.premiumcars.importing.source.autotrader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.ListingGoneException;
import com.premiumcars.importing.PageFetcher;

class AutotraderSourceAdapterTest {

    private static final String BMW = AutotraderPageParserTest.BMW;
    private static final String AUDI = AutotraderPageParserTest.AUDI;

    private static final class FakePages extends PageFetcher {
        final List<URI> requested = new ArrayList<>();
        String answer;

        FakePages() {
            super(null);
        }

        @Override
        public String fetchHtml(URI url) {
            requested.add(url);
            try {
                return answer != null ? answer : AutotraderPageParserTest.page(AutotraderPageParser.listingId(url.toString()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final FakePages pages = new FakePages();
    private final AutotraderSourceAdapter adapter = new AutotraderSourceAdapter(pages);

    @Test
    void testSupports_AutotraderOfferLinks_AreTrueOthersFalse() {
        assertThat(adapter.supports(URI.create("https://www.autotrader.ca/offers/x-" + BMW))).isTrue();
        assertThat(adapter.supports(URI.create("https://autotrader.ca/offers/bmw-x3-" + BMW))).isTrue();
        assertThat(adapter.supports(URI.create("https://www.autotrader.ca/cars/bmw/"))).isFalse();
        assertThat(adapter.supports(URI.create("https://www.autotrader.com/offers/x-" + BMW))).isFalse();
        assertThat(adapter.source()).isEqualTo(CarSource.AUTOTRADER_CA);
    }

    @Test
    void testListingRefAndCanonicalUrl_SlugLink_AreTheUuidAndShortLink() {
        URI slug = URI.create("https://www.autotrader.ca/offers/audi-e-tron-gt-quattro-nav-massage-3dcam-"
                + "redleather-bangolufsen-+-electric-grey-cat_ma9gr75926-" + AUDI + "?utm=a");
        URI shortLink = URI.create("https://www.autotrader.ca/offers/x-" + AUDI);

        assertThat(adapter.canonicalUrl(slug)).isEqualTo(shortLink);
        assertThat(adapter.canonicalUrl(shortLink)).isEqualTo(shortLink);
        assertThat(adapter.listingRef(adapter.canonicalUrl(slug))).isEqualTo(AUDI);
        assertThat(adapter.listingRef(shortLink)).isEqualTo(AUDI);
    }

    @Test
    void testPolicy_Source_LinksPhotosAndRequiresPrice() {
        assertThat(adapter.linksPhotos()).isTrue();
        assertThat(adapter.requiresPrice()).isTrue();
    }

    @Test
    void testImportFrom_Listing_FetchesShortLinkAndReturnsCadPrice() {
        ImportedCar car = adapter.importFrom(URI.create("https://www.autotrader.ca/offers/bmw-x3-gasoline-" + BMW));

        assertThat(pages.requested).containsExactly(URI.create("https://www.autotrader.ca/offers/x-" + BMW));
        assertThat(car.sourceRef()).isEqualTo(BMW);
        assertThat(car.sourceLocation()).isEqualTo("Edmonton, AB");
        assertThat(car.price()).isEqualByComparingTo("19910");
        assertThat(car.currency()).isEqualTo("CAD");
        assertThat(car.imageUrls()).hasSize(35);
        assertThat(car.specs().make()).isEqualTo("BMW");
        assertThat(car.warnings()).anyMatch(w -> w.contains("emission class"))
                .anyMatch(w -> w.contains("2026-11-18"));
    }

    @Test
    void testImportFrom_ElectricListing_WarnsAboutBatteryAndNoEuroWarning() {
        ImportedCar car = adapter.importFrom(URI.create("https://www.autotrader.ca/offers/x-" + AUDI));

        assertThat(car.warnings()).contains("Battery capacity not found.", "Electric range not found.")
                .noneMatch(w -> w.contains("emission class"));
    }

    @Test
    void testImportFrom_CallForPrice_ReturnsNoPriceAndNoCurrency() throws IOException {
        pages.answer = AutotraderPageParserTest.page(BMW).replace("\"priceRaw\":19910", "\"priceRaw\":1");

        ImportedCar car = adapter.importFrom(URI.create("https://www.autotrader.ca/offers/x-" + BMW));

        assertThat(car.price()).isNull();
        assertThat(car.currency()).isNull();
        assertThat(car.warnings().get(0)).contains("no price");
    }

    @Test
    void testImportFrom_RemovedListing_ThrowsListingGone() {
        pages.answer = "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"numberOfResults\":3,\"listings\":[]}}}</script></body></html>";

        assertThatThrownBy(() -> adapter.importFrom(URI.create("https://www.autotrader.ca/offers/x-" + AUDI)))
                .isInstanceOf(ListingGoneException.class);
    }

    @Test
    void testImportFrom_CheckPageWithoutData_ThrowsBadRequestNotGone() {
        pages.answer = "<html><body>Please verify you are human</body></html>";

        assertThatThrownBy(() -> adapter.importFrom(URI.create("https://www.autotrader.ca/offers/x-" + AUDI)))
                .isInstanceOf(BadRequestException.class).isNotInstanceOf(ListingGoneException.class);
    }

    @Test
    void testImportFrom_NotAnOfferLink_ThrowsBadRequest() {
        assertThatThrownBy(() -> adapter.importFrom(URI.create("https://www.autotrader.ca/cars/bmw/")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Not an autotrader.ca car page");
    }

    @Test
    void testImportFromHtml_SavedPage_IsReadWithoutFetching() throws IOException {
        ImportedCar car = adapter.importFromHtml(URI.create("https://www.autotrader.ca/offers/x-" + AUDI),
                AutotraderPageParserTest.page(AUDI));

        assertThat(pages.requested).isEmpty();
        assertThat(car.sourceRef()).isEqualTo(AUDI);
        assertThat(car.price()).isEqualByComparingTo("69995");
    }

    @Test
    void testImportFromHtml_PageOfAnotherListing_IsRefused() throws IOException {
        assertThatThrownBy(() -> adapter.importFromHtml(URI.create("https://www.autotrader.ca/offers/x-" + AUDI),
                AutotraderPageParserTest.page(BMW)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not this autotrader.ca listing");
    }
}
