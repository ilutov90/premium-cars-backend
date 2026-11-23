package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Year;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.source.guazi.GuaziPageParser;

/**
 * guazi's search through the API its site uses (no login):
 * {@code POST https://en.guazi.com/os/facade/search/product/list?language=en} with {@code brandId}, {@code seriesIds}
 * (models), {@code mfgDateStart/End} (year of manufacture), {@code detectionLevels} (grades) and paging; the makes
 * and their models (series) from {@code GET /os/product/filter/queryBrandList}.
 * <p>
 * guazi serves only the first {@value #WINDOW} results of a search. A make with more is read model by model, and a
 * model with more year by year; the make's own total tells whether everything was read. When too few were read,
 * the searches that gave fewer listings than counted (or a model counted 0) are read once more after a pause; a
 * make still under 90 % of its count is reported failed, so nothing of it is removed.
 */
@Component
public class GuaziSearch implements SourceSearch {

    static final String SITE = "https://en.guazi.com";
    static final int PAGE_SIZE = 30; // guazi's maximum
    static final int WINDOW = 500;
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    private final SearchHttp http;
    private final Pages pages;
    private final long retryPauseMs;
    /** guazi wants a device id and a guid in every search; any value is accepted. */
    private final String deviceId = UUID.randomUUID().toString().replace("-", "");

    public GuaziSearch(ObjectMapper json, AppProperties props, AutoImportInstance instance,
                       com.premiumcars.importing.browser.BrowserFetch browser) {
        // a bot check instead of data: asked again from the browser on guazi's own page
        this.http = new SearchHttp(json, instance.guaziSearchPause().toMillis(), Map.of(
                "User-Agent", props.importer().userAgent(),
                "Origin", SITE,
                "Referer", SITE + "/used-cars/"), browser, SITE + "/used-cars/");
        this.pages = (slice, page, size, recommendId) -> ok(http.post(
                URI.create(SITE + "/os/facade/search/product/list?language=en"),
                slice.body(page, size, deviceId, recommendId)));
        this.retryPauseMs = RETRY_PAUSE_MS;
    }

    /** For tests: the search's answers from elsewhere, the makes' models given with the {@link Brand}. */
    GuaziSearch(Pages pages, long retryPauseMs) {
        this.http = null;
        this.pages = pages;
        this.retryPauseMs = retryPauseMs;
    }

    /** The wait before the searches that gave too few listings are read again. */
    static final long RETRY_PAUSE_MS = 30_000;

    @Override
    public CarSource source() {
        return CarSource.GUAZI;
    }

    @Override
    public List<CatalogBrand> brands() {
        return brandList(ok(http.get(URI.create(SITE + "/os/product/filter/queryBrandList?language=en"))));
    }

    @Override
    public List<CatalogModel> models(String brandCode) {
        return brands().stream().filter(b -> b.code().equals(brandCode)).findFirst()
                .map(CatalogBrand::models).orElse(List.of());
    }

    @Override
    public int count(Brand brand, SourceFilters filters) {
        return total(new Slice(brand, filters, ids(brand.models()), brand.yearFrom(), null));
    }

    @Override
    public Instant lastRefused() {
        return http == null ? null : http.lastRefused();
    }

    @Override
    public BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found) {
        Set<String> seen = new HashSet<>();
        Consumer<FoundListing> once = l -> {
            if (seen.add(l.id())) {
                found.accept(l);
            }
        };
        int expected = 0;
        boolean complete = true;
        List<Read> reads = new ArrayList<>();
        try {
            Slice whole = new Slice(brand, filters, ids(brand.models()), brand.yearFrom(), null);
            expected = total(whole);
            if (expected <= WINDOW) {
                reads.add(readSlice(whole, expected, brand.name(), false, once));
            } else {
                for (CatalogModel m : brand.models().isEmpty() ? models(brand.code()) : brand.models()) {
                    complete &= readModel(brand, filters, m, once, reads);
                }
            }
            if (seen.size() < expected * COMPLETE_SHARE) {
                // guazi sometimes answers a search with fewer listings than it counts, or with none: read those again
                List<Read> again = reads.stream().filter(Read::suspect).toList();
                if (!again.isEmpty()) {
                    pause();
                    reads.removeAll(again);
                    for (Read r : again) {
                        if (r.modelLevel() && r.total() == 0) {
                            complete &= readModel(brand, filters, r.model(), once, reads);
                        } else {
                            reads.add(readSlice(r.slice(), total(r.slice()), r.label(), r.modelLevel(), once)
                                    .withModel(r.model()));
                        }
                    }
                }
            }
        } catch (SearchException e) {
            return new BrandPass(brand.code(), brand.name(), expected, seen.size(), false, e.getMessage(), true);
        }
        complete = complete && seen.size() >= expected * COMPLETE_SHARE;
        if (complete) {
            return new BrandPass(brand.code(), brand.name(), expected, seen.size(), true, null, false);
        }
        String shortReads = reads.stream().filter(r -> r.got() < Math.min(r.total(), WINDOW))
                .limit(5).map(r -> r.label() + " " + r.got() + "/" + r.total()).collect(Collectors.joining(", "));
        String error = "read " + seen.size() + " of " + expected + (shortReads.isEmpty() ? "" : "; short: " + shortReads);
        // far too few even after reading again: guazi did not answer properly, so nothing of the make counts as gone
        boolean unreliable = seen.size() < expected * UNRELIABLE_SHARE;
        return new BrandPass(brand.code(), brand.name(), expected, seen.size(), false, error, unreliable);
    }

    /** Reads one model: whole, or year by year (and grade by grade) when guazi would not show it all. */
    private boolean readModel(Brand brand, SourceFilters filters, CatalogModel m, Consumer<FoundListing> once,
                              List<Read> reads) {
        Slice model = new Slice(brand, filters, List.of(m.code()), brand.yearFrom(), null);
        int n = total(model);
        if (n <= WINDOW) {
            reads.add(readSlice(model, n, m.name(), true, once).withModel(m));
            return true;
        }
        boolean complete = true;
        int lastYear = Year.now(CHINA).getValue() + 1;
        for (int year = brand.yearFrom(); year <= lastYear; year++) {
            Slice oneYear = new Slice(brand, filters, List.of(m.code()), year, year);
            int y = total(oneYear);
            if (y <= WINDOW) {
                reads.add(readSlice(oneYear, y, m.name() + " " + year, false, once).withModel(m));
                continue;
            }
            // one model and one year with more than guazi shows: one grade at a time
            List<List<String>> grades = filters.grades().isEmpty()
                    ? List.of(List.of("S"), List.of("A"), List.of("B"), List.of("C"), List.of("D"))
                    : filters.grades().stream().map(List::of).toList();
            int byGrade = 0;
            for (List<String> g : grades) {
                Slice oneGrade = new Slice(brand, new SourceFilters(filters.inspected(),
                        filters.actualMileage(), g), List.of(m.code()), year, year);
                int k = total(oneGrade);
                byGrade += k;
                reads.add(readSlice(oneGrade, k, m.name() + " " + year + " " + g.get(0), false, once).withModel(m));
                if (k > WINDOW) {
                    complete = false; // still more than guazi shows
                }
            }
            if (filters.grades().isEmpty() && byGrade < y) {
                complete = false; // listings without a grade: not all reachable
            }
        }
        return complete;
    }

    /** The share of a make's count that must be read for the make to count as read whole. */
    static final double COMPLETE_SHARE = 0.98;
    /** Below this share, even after reading again, nothing of the make is removed. */
    static final double UNRELIABLE_SHARE = 0.9;

    /**
     * One search read: its count and how many listings its pages gave.
     *
     * @param modelLevel the search of a whole model: a count of 0 there may be guazi's empty answer
     */
    record Read(Slice slice, String label, boolean modelLevel, CatalogModel model, int total, int got) {

        /** Fewer listings than counted (up to what guazi shows), or a model guazi counted none of. */
        boolean suspect() {
            return got < Math.min(total, WINDOW) || (modelLevel && total == 0);
        }

        Read withModel(CatalogModel m) {
            return new Read(slice, label, modelLevel, m, total, got);
        }
    }

    private void pause() {
        if (retryPauseMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryPauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchException("interrupted");
        }
    }

    /** Auctions show guazi's stand-in price ("$9,999,999"): not imported, but still at the source. */
    @Override
    public boolean priced(FoundListing listing) {
        return GuaziPageParser.realPrice(listing.price());
    }

    /**
     * The order the pages are read in: newest listings first ("Newly launched" on the site). Without it guazi serves
     * "Best match", a recommendation order that changes while the pages are read: about a tenth of the listings were
     * missed by each run (and others shown twice), never the same ones, so no make was ever read whole.
     */
    static final String SORT = "created_at desc";

    /** One search: a make, maybe some models, a year range. */
    record Slice(Brand brand, SourceFilters filters, List<String> seriesIds, int yearFrom, Integer yearTo) {

        Map<String, Object> body(int pageNum, int pageSize, String deviceId) {
            return body(pageNum, pageSize, deviceId, null);
        }

        /** @param recommendId the previous page's {@code data.recommendId} (the site sends it from page 2 on) */
        Map<String, Object> body(int pageNum, int pageSize, String deviceId, String recommendId) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("language", "en");
            b.put("sort", SORT);
            b.put("brandId", brand.code());
            if (!seriesIds.isEmpty()) {
                b.put("seriesIds", seriesIds);
            }
            if (!filters.grades().isEmpty()) {
                b.put("detectionLevels", filters.grades());
            }
            b.put("mfgDateStart", yearFrom);
            if (yearTo != null) {
                b.put("mfgDateEnd", yearTo);
            }
            b.put("businessType", 5); // the "All" tab
            b.put("clientScene", "cars");
            b.put("sourceFrom", "wap");
            b.put("countryCode", "");
            b.put("did", deviceId);
            b.put("guid", deviceId);
            b.put("pageSize", pageSize);
            b.put("pageNum", pageNum);
            if (recommendId != null && !recommendId.isBlank()) {
                b.put("recommendId", recommendId);
            }
            return b;
        }
    }

    private int total(Slice slice) {
        return search(slice, 1, 1).path("totalCount").asInt();
    }

    /**
     * Reads a slice's pages (at most the first {@value #WINDOW} results). A page with fewer listings than asked does
     * not end it (guazi leaves some out now and then): only an empty page or the slice's count does.
     */
    private Read readSlice(Slice slice, int total, String label, boolean modelLevel, Consumer<FoundListing> found) {
        int reachable = Math.min(total, WINDOW);
        Set<String> got = new HashSet<>();
        String recommendId = null;
        for (int page = 1; (page - 1) * PAGE_SIZE < reachable; page++) {
            JsonNode data = search(slice, page, PAGE_SIZE, recommendId);
            recommendId = data.path("recommendId").asText(null);
            JsonNode list = data.path("list");
            for (JsonNode item : list) {
                FoundListing l = listing(item);
                if (l != null) {
                    got.add(l.id());
                    found.accept(l);
                }
            }
            if (list.isEmpty()) {
                break;
            }
        }
        return new Read(slice, label, modelLevel, null, total, got.size());
    }

    private JsonNode search(Slice slice, int page, int size) {
        return search(slice, page, size, null);
    }

    private JsonNode search(Slice slice, int page, int size, String recommendId) {
        return pages.search(slice, page, size, recommendId);
    }

    /** One page of a search's answer ({@code data}). */
    interface Pages {
        JsonNode search(Slice slice, int page, int size, String recommendId);
    }

    static List<String> ids(List<CatalogModel> models) {
        return models.stream().map(CatalogModel::code).toList();
    }

    static FoundListing listing(JsonNode item) {
        String id = item.path("productId").asText("");
        String seo = item.path("seoUri").asText("");
        if (id.isBlank() || seo.isBlank()) {
            return null;
        }
        String url = GuaziPageParser.englishUrl(URI.create(SITE + "/products/" + seo)).toString();
        return new FoundListing(id, url, title(item.path("title").asText(null)), price(item.path("price").asText("")),
                mileage(seo));
    }

    private static final Pattern SEO_KM = Pattern.compile("-(\\d{1,7})km-");

    /** The mileage in the listing's address: "...-gray-36200km-at-2wd-..." -> 36200. */
    static Integer mileage(String seoUri) {
        Matcher m = SEO_KM.matcher(seoUri);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** "Used BYD Song PLUS 2021 ..." -> "BYD Song PLUS 2021 ...". */
    static String title(String title) {
        return title == null ? null : title.replaceFirst("^Used\\s+", "");
    }

    /** "$15,588" -> 15588. */
    static BigDecimal price(String text) {
        String digits = text.replaceAll("[^0-9.]", "");
        return digits.isEmpty() ? null : new BigDecimal(digits);
    }

    /** {"code":0,...,"data":{...}}; guazi reports errors as HTTP 200 with another code. */
    static JsonNode ok(JsonNode answer) {
        if (answer.path("code").asInt(-1) != 0) {
            throw new SearchException("guazi: code " + answer.path("code").asText("?") + " "
                    + answer.path("message").asText(""));
        }
        return answer.path("data");
    }

    /** {"brands":{"A":[{"id","name","tags":[{"id","name"}]}], "B":[...]}} -> makes with their models. */
    static List<CatalogBrand> brandList(JsonNode data) {
        List<CatalogBrand> out = new ArrayList<>();
        data.path("brands").forEach(letter -> letter.forEach(b -> {
            List<CatalogModel> models = new ArrayList<>();
            b.path("tags").forEach(t -> models.add(new CatalogModel(t.path("id").asText(), t.path("name").asText())));
            out.add(new CatalogBrand(b.path("id").asText(), b.path("name").asText(), models));
        }));
        return out;
    }
}
