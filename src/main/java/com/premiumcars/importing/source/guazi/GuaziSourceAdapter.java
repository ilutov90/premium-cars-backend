package com.premiumcars.importing.source.guazi;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.PageFetcher;
import com.premiumcars.importing.SourceAdapter;
import com.premiumcars.importing.source.guazi.GuaziPageParser.ParsedPage;

/**
 * en.guazi.com (Guazi's export site, cars in China). No login, no browser: the page's HTML holds the whole listing
 * (see {@link GuaziPageParser}). Prices are FOB in USD; the car is priced with the autohome formula.
 * <p>
 * guazi may answer the server with a bot check (a script page) instead of the listing. The admin then saves the page
 * in their own browser and imports that file ({@link #importFromHtml}); the server does not try to get past the
 * check.
 */
@Component
@Order(12)
public class GuaziSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(GuaziSourceAdapter.class);

    private final PageFetcher fetcher;

    public GuaziSourceAdapter(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public CarSource source() {
        return CarSource.GUAZI;
    }

    @Override
    public boolean supports(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("guazi.com") || host.endsWith(".guazi.com");
    }

    /** The English page without query: the same listing in another language or with tracking is a duplicate. */
    @Override
    public URI canonicalUrl(URI url) {
        return GuaziPageParser.englishUrl(url);
    }

    @Override
    public String referer(URI listingUrl) {
        return "https://en.guazi.com/";
    }

    @Override
    public List<String> imageCandidates(String imageUrl) {
        return GuaziPageParser.imageCandidates(imageUrl);
    }

    @Override
    public ImportedCar importFrom(URI url) {
        URI page = canonicalUrl(url);
        String id = GuaziPageParser.productId(page.toString());
        if (id == null) {
            throw new BadRequestException("Not a guazi car page (expected https://en.guazi.com/products/...html): "
                    + url);
        }
        return fromPage(fetcher.fetchHtml(page), id, false);
    }

    @Override
    public ImportedCar importFromHtml(URI url, String html) {
        String id = GuaziPageParser.productId(canonicalUrl(url).toString());
        if (id == null) {
            throw new BadRequestException("Not a guazi car page (expected https://en.guazi.com/products/...html): "
                    + url);
        }
        return fromPage(html, id, true);
    }

    private ImportedCar fromPage(String html, String id, boolean saved) {
        ParsedPage parsed;
        try {
            parsed = GuaziPageParser.parse(html, id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(saved
                    ? "The saved file is not this guazi listing: " + e.getMessage()
                    : "guazi did not send the listing to the server (bot check): save the page in your browser and "
                      + "import the file. " + e.getMessage(), e);
        }
        if (saved && !id.equalsIgnoreCase(parsed.productId())) {
            throw new BadRequestException("The saved file is listing " + parsed.productId() + ", not " + id);
        }
        CarSpecs specs = GuaziPageParser.toSpecs(parsed);

        List<String> warnings = new ArrayList<>();
        if (parsed.priceUsd() == null) {
            warnings.add("Price not found: enter it by hand.");
        }
        if (specs.fuelType() != null && specs.fuelType().hasTractionBattery()) {
            if (specs.batteryKwh() == null) {
                warnings.add("Battery capacity not found.");
            }
            if (specs.electricRangeKm() == null) {
                warnings.add("Electric range not found.");
            }
        }
        if (specs.euroStandard() == null && specs.fuelType() != null
                && specs.fuelType() != FuelType.ELECTRIC) {
            warnings.add("guazi does not give the emission class (Euro): set it for mobile.bg.");
        }
        if (parsed.inspection() != null) {
            warnings.add(parsed.inspection());
        }
        log.info("guazi {}: {} | {} USD | {} photos", parsed.productId(), specs.title(), parsed.priceUsd(),
                parsed.images().size());
        return new ImportedCar(parsed.productId(), parsed.fields().get("location"), specs, parsed.priceUsd(),
                parsed.priceUsd() == null ? null : "USD", parsed.images(), warnings);
    }
}
