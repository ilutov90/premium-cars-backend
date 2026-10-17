package com.premiumcars.importing.source.autowini;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.SourceAdapter;
import com.premiumcars.importing.source.autowini.AutowiniPageParser.ParsedPage;

/**
 * autowini.com (South Korea). Pages are read with a logged-in session ({@link AutowiniClient}).
 * Prices are shown only to some members, so the admin usually enters the price on import.
 */
@Component
@Order(20)
public class AutowiniSourceAdapter implements SourceAdapter {

    private final AutowiniClient client;

    public AutowiniSourceAdapter(AutowiniClient client) {
        this.client = client;
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOWINI;
    }

    @Override
    public boolean supports(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("autowini.com") || host.endsWith(".autowini.com");
    }

    @Override
    public URI canonicalUrl(URI url) {
        String id = AutowiniPageParser.itemId(url.toString());
        return id == null ? url : URI.create(AutowiniPageParser.desktopUrl(id));
    }

    @Override
    public String referer(URI listingUrl) {
        return "https://www.autowini.com/";
    }

    @Override
    public List<String> imageCandidates(String imageUrl) {
        return AutowiniPageParser.imageCandidates(imageUrl);
    }

    @Override
    public ImportedCar importFrom(URI url) {
        String id = AutowiniPageParser.itemId(url.toString());
        if (id == null) {
            throw new BadRequestException("Not an autowini listing link (expected .../Cars/IC1234567/...)");
        }
        String html = client.fetchListing(AutowiniPageParser.desktopUrl(id), AutowiniPageParser.mobileUrl(id));
        ParsedPage page = AutowiniPageParser.parse(html, id);
        CarSpecs specs = AutowiniPageParser.toSpecs(page);

        List<String> warnings = new ArrayList<>();
        if (specs.fuelType() != null && specs.fuelType().hasTractionBattery()) {
            warnings.add("autowini does not list battery capacity or electric range: enter them manually.");
        }
        return new ImportedCar(page.itemId(), page.fields().get("location"), specs, page.price(), page.currency(),
                page.images(), warnings);
    }
}
