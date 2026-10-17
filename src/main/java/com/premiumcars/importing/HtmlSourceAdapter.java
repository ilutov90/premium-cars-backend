package com.premiumcars.importing;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;

import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.extract.ExtractionHints;
import com.premiumcars.importing.extract.GenericPageExtractor;

/** Base for adapters that read a public HTML page. Subclasses only describe their site. */
public abstract class HtmlSourceAdapter implements SourceAdapter {

    private static final Pattern LONG_NUMBER = Pattern.compile("(\\d{5,})");

    protected final PageFetcher fetcher;
    protected final GenericPageExtractor extractor;
    protected final AppProperties props;

    protected HtmlSourceAdapter(PageFetcher fetcher, GenericPageExtractor extractor, AppProperties props) {
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.props = props;
    }

    @Override
    public ImportedCar importFrom(URI url) {
        Document doc = fetcher.fetch(url);
        ImportedCar raw = extractor.extract(doc, hints());
        return withSourceRef(raw, sourceRef(url));
    }

    protected abstract ExtractionHints hints();

    /** Listing id on the source site; by default the longest number in the URL. */
    protected String sourceRef(URI url) {
        Matcher m = LONG_NUMBER.matcher(url.getPath() + "?" + (url.getQuery() == null ? "" : url.getQuery()));
        String best = null;
        while (m.find()) {
            if (best == null || m.group(1).length() > best.length()) {
                best = m.group(1);
            }
        }
        return best;
    }

    protected static boolean hostEndsWith(URI url, String... domains) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    protected static boolean looksLikeDecoration(String imageUrl) {
        String u = imageUrl.toLowerCase(Locale.ROOT);
        return u.contains("logo") || u.contains("icon") || u.contains("avatar") || u.contains("banner")
                || u.contains("/_next/static/") || u.contains("sprite") || u.contains("qrcode");
    }

    private static ImportedCar withSourceRef(ImportedCar c, String ref) {
        return new ImportedCar(ref, c.title(), c.make(), c.model(), c.trim(), c.year(), c.mileageKm(), c.price(),
                c.currency(), c.fuelType(), c.transmission(), c.bodyType(), c.engine(), c.color(), c.description(),
                c.specs(), c.imageUrls());
    }
}
