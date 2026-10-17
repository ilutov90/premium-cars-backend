package com.premiumcars.importing.source.autohome;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.PageFetcher;
import com.premiumcars.importing.SourceAdapter;
import com.premiumcars.importing.browser.PageRenderer;
import com.premiumcars.importing.browser.PageRenderer.RenderedPage;
import com.premiumcars.importing.extract.EvSpecsExtractor;
import com.premiumcars.importing.extract.EvSpecsExtractor.EvSpecs;
import com.premiumcars.importing.extract.PageText;
import com.premiumcars.importing.source.autohome.AutohomePageParser.ParsedPage;

/**
 * global.autohome.com.cn (also served as global.che168.com). No login; prices are in USD.
 * <p>
 * The plain HTML has the vehicle details but usually only the first photo, and no electric drive data:
 * the gallery and the "Electric Motor" / "Battery && Charging" tabs load with JavaScript. When something is
 * missing, the page is rendered once in a headless browser (see {@link PageRenderer}).
 */
@Component
@Order(10)
public class AutohomeSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(AutohomeSourceAdapter.class);

    /** Captions clicked in the browser, in order: "More" opens all details, then the EV tabs. */
    private static final List<Pattern> EV_CLICKS = List.of(
            Pattern.compile("^\\s*More\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("View\\s+(all|more)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(Configuration|Specifications|Parameters)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*Electric\\s*Motor\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Batter(y|ies)\\s*&*\\s*Charging", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*Battery\\s*$", Pattern.CASE_INSENSITIVE));

    private final PageFetcher fetcher;
    private final PageRenderer renderer;

    public AutohomeSourceAdapter(PageFetcher fetcher, PageRenderer renderer) {
        this.fetcher = fetcher;
        this.renderer = renderer;
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOHOME;
    }

    @Override
    public boolean supports(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith("autohome.com.cn") || host.endsWith("che168.com");
    }

    @Override
    public URI canonicalUrl(URI url) {
        String id = AutohomePageParser.listingId(url.toString());
        return id == null ? url : URI.create("https://global.autohome.com.cn/en/detail/" + id);
    }

    @Override
    public String referer(URI listingUrl) {
        return "https://global.autohome.com.cn/";
    }

    @Override
    public List<String> imageCandidates(String imageUrl) {
        return AutohomePageParser.imageCandidates(imageUrl);
    }

    @Override
    public ImportedCar importFrom(URI url) {
        String pageUrl = AutohomePageParser.englishUrl(canonicalUrl(url).toString());
        String listingId = AutohomePageParser.listingId(pageUrl);
        ParsedPage page = AutohomePageParser.parse(fetcher.fetchHtml(URI.create(pageUrl)), listingId);

        FuelType fuel = page.fuelType();
        boolean wantEv = fuel != null && fuel.hasTractionBattery();
        EvSpecs ev = wantEv ? EvSpecsExtractor.extract(page.tokenLists()) : EvSpecs.NONE;
        List<String> images = page.images();
        Integer total = page.galleryTotal();

        boolean needImages = (total != null && images.size() < total) || images.size() < 3;
        boolean needEv = wantEv && !ev.complete();
        List<String> warnings = new ArrayList<>();

        if ((needImages || needEv) && renderer.enabled()) {
            Optional<RenderedPage> rendered = renderer.render(pageUrl, needEv ? EV_CLICKS : List.of(),
                    needEv ? AutohomePageParser.EV_HINT : null);
            if (rendered.isPresent()) {
                RenderedPage r = rendered.get();
                ParsedPage again = AutohomePageParser.parse(r.html(), listingId);
                if (again.images().size() > images.size()) {
                    images = again.images();
                    total = again.galleryTotal() != null ? again.galleryTotal() : total;
                }
                if (needEv) {
                    List<List<String>> more = new ArrayList<>(again.tokenLists());
                    for (String text : r.texts()) {
                        more.add(PageText.splitLines(text));
                        more.add(EvSpecsExtractor.jsonTokens(text));
                    }
                    ev = ev.or(EvSpecsExtractor.extract(more));
                }
            } else {
                warnings.add("The page could not be opened in the browser; photos or EV data may be missing.");
            }
        } else if (needImages || needEv) {
            warnings.add("Browser rendering is off (app.importer.browser.enabled); photos or EV data may be missing.");
        }

        CarSpecs specs = AutohomePageParser.toSpecs(page, ev);
        if (fuel != null && fuel.hasTractionBattery()) {
            if (specs.batteryKwh() == null) {
                warnings.add("Battery capacity not found.");
            }
            if (specs.electricRangeKm() == null) {
                warnings.add("Electric range not found.");
            }
            if (specs.motorPowerKw() == null) {
                warnings.add("Motor power not found.");
            }
        }
        if (total != null && images.size() < total) {
            warnings.add("Found " + images.size() + " of " + total + " photos.");
        }
        log.info("autohome {}: {} | {} photos | EV {}", listingId, specs.title(), images.size(), ev);
        return new ImportedCar(listingId, page.fields().get("location"), specs, page.priceUsd(),
                page.priceUsd() == null ? null : "USD", images, warnings);
    }
}
