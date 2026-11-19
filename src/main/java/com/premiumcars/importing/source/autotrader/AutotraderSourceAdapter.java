package com.premiumcars.importing.source.autotrader;

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
import com.premiumcars.importing.source.autotrader.AutotraderPageParser.ParsedPage;

/**
 * www.autotrader.ca (used cars in Canada, prices in CAD, read with Trader Corporation's permission). No login, no
 * browser: the listing page's HTML holds the whole listing (see {@link AutotraderPageParser}). The car is priced with
 * the AUTOTRADER_CA formula from the CAD price.
 * <p>
 * A removed listing redirects to a results page: {@link com.premiumcars.importing.ListingGoneException}.
 */
@Component
@Order(14)
public class AutotraderSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(AutotraderSourceAdapter.class);

    private final PageFetcher fetcher;

    public AutotraderSourceAdapter(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOTRADER_CA;
    }

    @Override
    public boolean supports(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        return (host.equals("autotrader.ca") || host.equals("www.autotrader.ca"))
                && AutotraderPageParser.listingId(url.toString()) != null;
    }

    /** The listing's UUID: the slug in front of it changes with the seller's edits, the UUID does not. */
    @Override
    public String listingRef(URI canonicalUrl) {
        return AutotraderPageParser.listingId(canonicalUrl.toString());
    }

    @Override
    public URI canonicalUrl(URI url) {
        String id = AutotraderPageParser.listingId(url.toString());
        return id == null ? url : AutotraderPageParser.canonicalUrl(id);
    }

    @Override
    public String referer(URI listingUrl) {
        return "https://www.autotrader.ca/";
    }

    /** autotrader's photo CDN serves any visitor, no cookies or referer: the site shows the photos from there. */
    @Override
    public boolean linksPhotos() {
        return true;
    }

    @Override
    public List<String> imageCandidates(String imageUrl) {
        return List.of(imageUrl);
    }

    /** A "call for price" listing (price 1 and the like) is not imported unless the admin enters the price. */
    @Override
    public boolean requiresPrice() {
        return true;
    }

    @Override
    public ImportedCar importFrom(URI url) {
        String id = idOf(url);
        String html = fetcher.fetchHtml(AutotraderPageParser.canonicalUrl(id));
        return fromPage(html, id, false);
    }

    @Override
    public ImportedCar importFromHtml(URI url, String html) {
        return fromPage(html, idOf(url), true);
    }

    private static String idOf(URI url) {
        String id = AutotraderPageParser.listingId(url.toString());
        if (id == null) {
            throw new BadRequestException(
                    "Not an autotrader.ca car page (expected https://www.autotrader.ca/offers/...-{id}): " + url);
        }
        return id;
    }

    private ImportedCar fromPage(String html, String id, boolean saved) {
        ParsedPage parsed;
        try {
            parsed = AutotraderPageParser.parse(html, id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(saved
                    ? "The saved file is not this autotrader.ca listing: " + e.getMessage()
                    : "Could not read the autotrader.ca page: " + e.getMessage(), e);
        }
        CarSpecs specs = parsed.specs();

        List<String> warnings = new ArrayList<>();
        if (parsed.priceCad() == null) {
            warnings.add("autotrader.ca shows no price for this listing (call for price?).");
        }
        if (specs.fuelType() != null && specs.fuelType().hasTractionBattery()) {
            if (specs.batteryKwh() == null) {
                warnings.add("Battery capacity not found.");
            }
            if (specs.electricRangeKm() == null) {
                warnings.add("Electric range not found.");
            }
        }
        if (specs.euroStandard() == null && specs.fuelType() != null && specs.fuelType() != FuelType.ELECTRIC) {
            warnings.add("autotrader.ca does not give the emission class (Euro): set it for mobile.bg.");
        }
        if (parsed.listedOn() != null) {
            warnings.add("Listed on autotrader.ca since " + parsed.listedOn() + ".");
        }
        log.info("autotrader.ca {}: {} | {} CAD | {} photos", parsed.id(), specs.title(), parsed.priceCad(),
                parsed.images().size());
        return new ImportedCar(parsed.id(), parsed.location(), specs, parsed.priceCad(),
                parsed.priceCad() == null ? null : "CAD", parsed.images(), warnings);
    }
}
