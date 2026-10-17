package com.premiumcars.importing.source;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.PageFetcher;
import com.premiumcars.importing.SourceAdapter;
import com.premiumcars.importing.extract.ExtractionHints;
import com.premiumcars.importing.extract.GenericPageExtractor;

/** Fallback for any other site: best-effort extraction, the admin fills in the rest. Always last. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class GenericSourceAdapter implements SourceAdapter {

    private static final Pattern LONG_NUMBER = Pattern.compile("(\\d{5,})");

    private final PageFetcher fetcher;
    private final GenericPageExtractor extractor;
    private final AppProperties props;

    public GenericSourceAdapter(PageFetcher fetcher, GenericPageExtractor extractor, AppProperties props) {
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.props = props;
    }

    @Override
    public CarSource source() {
        return CarSource.OTHER;
    }

    @Override
    public boolean supports(URI url) {
        return true;
    }

    @Override
    public ImportedCar importFrom(URI url) {
        var hints = new ExtractionHints("USD", GenericSourceAdapter::isCarPhoto, props.importer().maxImages());
        ImportedCar raw = extractor.extract(Jsoup.parse(fetcher.fetchHtml(url), url.toString()), hints);
        return new ImportedCar(longestNumber(url), null, raw.specs(), raw.price(), raw.currency(), raw.imageUrls(),
                raw.warnings());
    }

    private static boolean isCarPhoto(String imageUrl) {
        String u = imageUrl.toLowerCase(Locale.ROOT);
        return !(u.contains("logo") || u.contains("icon") || u.contains("avatar") || u.contains("banner")
                || u.contains("/_next/static/") || u.contains("sprite") || u.contains("qrcode"));
    }

    /** Listing id on the source site; by default the longest number in the URL. */
    private static String longestNumber(URI url) {
        Matcher m = LONG_NUMBER.matcher(url.getPath() + "?" + (url.getQuery() == null ? "" : url.getQuery()));
        String best = null;
        while (m.find()) {
            if (best == null || m.group(1).length() > best.length()) {
                best = m.group(1);
            }
        }
        return best;
    }
}
