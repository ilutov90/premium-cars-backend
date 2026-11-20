package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.autoimport.AutoImportTypes.AutotraderFilters;
import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.FuelOption;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.browser.BrowserFetch;

/**
 * autotrader.ca's search, read from its results pages as a visitor sees them (no login, not its internal GraphQL):
 * {@code GET https://www.autotrader.ca/cars?atype=C&cy=CA&cat=ma13&modelyearfrom=2018&offer=U&zip=N5P1C5&zipr=400
 * &sort=age&desc=1&size=100&page=1}; the data is the page's Next.js {@code __NEXT_DATA__} ({@code props.pageProps}:
 * {@code numberOfResults}, {@code pageQuery} = the filters as the site understood them, {@code listings[]},
 * {@code taxonomy}). Makes are {@code taxonomy.makesSorted}, a make's model groups {@code taxonomy.modelGroups[make]}
 * (filled when the make is searched); a model group is searched as {@code cat=ma13gr100038}. Prices are in CAD.
 * <p>
 * A search reaches only its first {@value #WINDOW} results: a bigger make is read model group by model group, and a
 * group with more year by year (newest posted first, {@code sort=age&desc=1}: the default order is boosted and shifts
 * while the pages are read). A filter the site did not take ({@code pageQuery} differs, or no listing has a distance
 * to the postal code) stops the make rather than read the wrong listings.
 */
@Component
public class AutotraderSearch implements SourceSearch {

    static final String SITE = "https://www.autotrader.ca";
    static final int PAGE_SIZE = 100;
    /** Pages asked only for their count or their lists: the site's smallest page. */
    static final int COUNT_SIZE = 20;
    static final int WINDOW = 4000;
    /** Dealers show 1 (or another stand-in) for "call for price". */
    static final BigDecimal MIN_PRICE = BigDecimal.valueOf(100);
    static final String UNSPECIFIED = "Unspecified";
    /** Every condition but new: what "used only" switched off searches. */
    static final String NOT_NEW = "U,D,J,O,S";
    private static final ZoneId CANADA = ZoneId.of("America/Toronto");

    /** The site's fuel types (code -> its name), in the order their counts are asked (the common ones first). */
    static final Map<String, String> FUELS = fuels();

    private static Map<String, String> fuels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("B", "Gasoline");
        m.put("2", "Gas/Electric Hybrid");
        m.put("E", "Electric");
        m.put("D", "Diesel");
        m.put("F", "Flex Fuel");
        m.put("C", "Natural Gas");
        m.put("L", "Propane (LPG)");
        m.put("O", "Others");
        return java.util.Collections.unmodifiableMap(m);
    }

    private final Function<URI, JsonNode> pages;
    private final SearchHttp http;

    @Autowired
    public AutotraderSearch(ObjectMapper json, AppProperties props, AutoImportInstance instance,
                            BrowserFetch browser) {
        // a page without its data (a bot check) is asked again from the headless browser on the site's own page
        this.http = new SearchHttp(json, instance.searchPause().toMillis(), Map.of(
                "User-Agent", props.importer().userAgent(),
                "Accept-Language", "en-CA,en;q=0.9"), browser, SITE + "/cars");
        this.pages = http::getNextData;
    }

    /** @param pages a page's {@code __NEXT_DATA__} by its address (tests) */
    AutotraderSearch(Function<URI, JsonNode> pages) {
        this.http = null;
        this.pages = pages;
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOTRADER_CA;
    }

    @Override
    public String currency() {
        return "CAD";
    }

    @Override
    public Instant lastRefused() {
        return http == null ? null : http.lastRefused();
    }

    @Override
    public List<CatalogBrand> brands() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("atype", "C");
        q.put("cy", "CA");
        q.put("size", String.valueOf(COUNT_SIZE));
        List<CatalogBrand> out = makes(pageProps(pages.apply(uri("/cars", q))).path("taxonomy"));
        if (out.isEmpty()) {
            throw new SearchException("autotrader.ca: no makes in the page");
        }
        return out;
    }

    @Override
    public List<CatalogModel> models(String brandCode) {
        Slice s = new Slice(brandCode, null, 1990, null, null, null);
        JsonNode pp = pageProps(pages.apply(s.uri(1, COUNT_SIZE)));
        return groups(pp.path("taxonomy"), brandCode).stream().filter(m -> !UNSPECIFIED.equals(m.name())).toList();
    }

    /**
     * A make's or model group's fuel types with their numbers of listings under the filters: one count per fuel
     * type (the site has no facet in its pages), until the counts add up to the whole.
     */
    @Override
    public List<FuelOption> fuels(String brandCode, String modelCode, int yearFrom, SourceFilters filters) {
        Slice base = new Slice(brandCode, modelCode == null || modelCode.isBlank() ? null : modelCode, yearFrom,
                null, null, filters.autotrader());
        int total = fetch(base, 1, COUNT_SIZE).total();
        List<FuelOption> out = new ArrayList<>();
        int sum = 0;
        for (Map.Entry<String, String> f : FUELS.entrySet()) {
            if (sum >= total) {
                break;
            }
            int n = fetch(base.withFuels(List.of(f.getKey())), 1, COUNT_SIZE).total();
            if (n > 0) {
                out.add(new FuelOption(f.getKey(), f.getValue(), n));
            }
            sum += n;
        }
        return out;
    }

    @Override
    public int count(Brand brand, SourceFilters filters) {
        int total = 0;
        for (Unit u : units(brand)) {
            if (u.fuels() != null && u.fuels().isEmpty()) {
                continue; // every fuel type of it left out
            }
            total += fetch(slice(brand, u, filters), 1, COUNT_SIZE).total();
        }
        return total;
    }

    @Override
    public BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found) {
        Set<String> seen = new HashSet<>();
        int expected = 0;
        boolean[] whole = {true};
        try {
            for (Unit u : units(brand)) {
                if (u.fuels() != null && u.fuels().isEmpty()) {
                    continue;
                }
                Reader r = new Reader(brand, u, seen, found, whole);
                expected += r.read(slice(brand, u, filters));
            }
        } catch (SearchException e) {
            return new BrandPass(brand.code(), brand.name(), expected, seen.size(), false, e.getMessage(), true);
        }
        // listings come and go while the pages are read: a few may slip between pages
        boolean complete = whole[0] && seen.size() >= expected * 0.98;
        return new BrandPass(brand.code(), brand.name(), expected, seen.size(), complete,
                complete ? null : "read " + seen.size() + " of " + expected, false);
    }

    /**
     * The listing's page ({@code /offers/x-{id}}: the site redirects it to the listing's own address) shows that
     * listing, active; a listing that is gone redirects to the model's results page.
     */
    @Override
    public Boolean listed(String listingId) {
        String id = listingId == null ? "" : listingId.trim().toLowerCase(Locale.ROOT);
        if (!UUID.matcher(id).matches()) {
            throw new SearchException("autotrader.ca: not a listing id: " + listingId);
        }
        return listedIn(pageProps(pages.apply(URI.create(SITE + "/offers/x-" + id))), id);
    }

    static Boolean listedIn(JsonNode pageProps, String id) {
        JsonNode d = pageProps.path("listingDetails");
        if (d.isObject()) {
            String status = d.path("status").asText("");
            return id.equalsIgnoreCase(d.path("id").asText("")) && (status.isEmpty() || "Active".equalsIgnoreCase(status));
        }
        if (pageProps.has("numberOfResults") || pageProps.has("listings")) {
            return false; // redirected to a results page: the listing is gone
        }
        throw new SearchException("autotrader.ca: neither a listing nor results in the page of " + id);
    }

    /** Only a real price: dealers' stand-ins below {@value #MIN_PRICE_TEXT} CAD mean "call for price". */
    @Override
    public boolean priced(FoundListing listing) {
        return listing.price() != null && listing.price().compareTo(MIN_PRICE) >= 0;
    }

    private static final String MIN_PRICE_TEXT = "100";

    // ------------------------------------------------------------------ reading

    /** One search unit of a make: a chosen model group (or the whole make) with the fuel types it keeps. */
    record Unit(String group, List<String> fuels, String model) {
    }

    /**
     * One per chosen model group with its fuel types left out, or the make with its own.
     * {@code fuels}: the codes to search (null = all; empty = every one left out).
     */
    static List<Unit> units(Brand brand) {
        if (brand.models().isEmpty()) {
            return List.of(new Unit(null, kept(brand.excludedFuels()), null));
        }
        return brand.models().stream().map(m -> new Unit(m.code(), kept(m.excludedFuels()), m.name())).toList();
    }

    /** The fuel codes not left out (left-out ones by their names or codes); null when none is left out. */
    static List<String> kept(List<String> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return null;
        }
        return FUELS.entrySet().stream()
                .filter(f -> !AutowiniSearch.excluded(excluded, f.getKey(), f.getValue()))
                .map(Map.Entry::getKey).toList();
    }

    private static Slice slice(Brand brand, Unit u, SourceFilters filters) {
        return new Slice(brand.code(), u.group(), brand.yearFrom(), null, u.fuels(), filters.autotrader());
    }

    /** A results page: the search's total, its listings and the page's data. */
    record Page(int total, List<JsonNode> items, JsonNode pageProps) {
    }

    /** Reads one unit's slices, splitting those over the window. */
    private final class Reader {
        private final Brand brand;
        private final Unit unit;
        private final Set<String> seen;
        private final Consumer<FoundListing> found;
        private final boolean[] whole;

        Reader(Brand brand, Unit unit, Set<String> seen, Consumer<FoundListing> found, boolean[] whole) {
            this.brand = brand;
            this.unit = unit;
            this.seen = seen;
            this.found = found;
            this.whole = whole;
        }

        /** @return the slice's total */
        int read(Slice s) {
            Page first = fetch(s, 1, PAGE_SIZE);
            if (first.total() <= WINDOW) {
                pages(s, first);
                return first.total();
            }
            if (s.group() == null) {
                List<CatalogModel> groups = groups(first.pageProps().path("taxonomy"), s.make());
                if (!groups.isEmpty()) {
                    for (CatalogModel g : groups) {
                        read(s.withGroup(g.code()));
                    }
                    return first.total();
                }
            }
            if (s.yearTo() == null) {
                int last = Year.now(CANADA).getValue() + 1;
                for (int y = s.yearFrom(); y <= last; y++) {
                    read(s.withYears(y, y));
                }
                return first.total();
            }
            // one model group's one year over the window: only its first results can be read
            pages(s, first);
            whole[0] = false;
            return first.total();
        }

        private void pages(Slice s, Page first) {
            int reachable = Math.min(first.total(), WINDOW);
            first.items().forEach(item -> take(s, item));
            for (int page = 2; (long) (page - 1) * PAGE_SIZE < reachable; page++) {
                Page p = fetch(s, page, PAGE_SIZE);
                if (p.items().isEmpty()) {
                    break;
                }
                p.items().forEach(item -> take(s, item));
            }
        }

        private void take(Slice s, JsonNode item) {
            FoundListing l = listing(item);
            if (l == null || !inSlice(s, item)) {
                return;
            }
            if (seen.add(l.id())) {
                found.accept(unit.model() != null ? l.withModel(unit.model()) : l);
            }
        }
    }

    /**
     * The result belongs to the search (the site may mix in promoted listings): its make and model group by the
     * result's own taxonomy ids, its fuel type and condition; what the result does not say is not held against it.
     */
    static boolean inSlice(Slice s, JsonNode item) {
        String taxonomy = item.path("tracking").path("modelTaxonomy").asText("");
        String make = taxonomyId(taxonomy, "make_id");
        if (make != null && !make.equals(s.make())) {
            return false;
        }
        String group = taxonomyId(taxonomy, "model_group_id");
        if (s.group() != null && group != null && !group.equals(s.group())) {
            return false;
        }
        String fuel = item.path("tracking").path("fuelType").asText("").trim().toUpperCase(Locale.ROOT);
        if (s.fuels() != null && !fuel.isEmpty() && !s.fuels().contains(fuel)) {
            return false;
        }
        String offer = item.path("vehicle").path("offerType").asText("");
        return !(s.filters() != null && s.filters().usedOnly() && !offer.isEmpty() && !"U".equalsIgnoreCase(offer))
                && !"N".equalsIgnoreCase(offer);
    }

    private static final Pattern TAXONOMY_ID = Pattern.compile("\\b(\\w+):(\\d+)");

    /** "[make_id:13, model_group_id:100042, variant_id:782, ...]" -> the id, or null. */
    static String taxonomyId(String modelTaxonomy, String name) {
        Matcher m = TAXONOMY_ID.matcher(modelTaxonomy == null ? "" : modelTaxonomy);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return m.group(2);
            }
        }
        return null;
    }

    Page fetch(Slice s, int page, int size) {
        JsonNode pp = pageProps(pages.apply(s.uri(page, size)));
        Page p = page(pp);
        check(s, pp, p.items());
        return p;
    }

    static Page page(JsonNode pageProps) {
        JsonNode total = pageProps.path("numberOfResults");
        if (!total.isNumber() && !(total.isTextual() && total.asText().matches("\\d{1,9}"))) {
            throw new SearchException("autotrader.ca: no search results in the page");
        }
        List<JsonNode> items = new ArrayList<>();
        pageProps.path("listings").forEach(items::add);
        return new Page(total.asInt(), items, pageProps);
    }

    /** The site took the search's make/model, fuel types and location (else the results are not the ones asked). */
    static void check(Slice s, JsonNode pageProps, List<JsonNode> items) {
        JsonNode q = pageProps.path("pageQuery");
        if (!s.cat().equalsIgnoreCase(q.path("cat").asText(""))) {
            throw new SearchException("autotrader.ca did not take the make/model filter " + s.cat() + " (it searched "
                    + q.path("cat").asText("everything") + ")");
        }
        if (s.fuels() != null && !codes(q.path("fuel").asText("")).equals(new HashSet<>(s.fuels()))) {
            throw new SearchException("autotrader.ca did not take the fuel filter " + String.join(",", s.fuels()));
        }
        if (s.filters() != null && !items.isEmpty()
                && items.stream().noneMatch(i -> i.path("location").path("distanceToSearchLocationInKm").isNumber())) {
            throw new SearchException("autotrader.ca did not take the postal code " + s.filters().postalCode()
                    + " (its results have no distance to it: the location may need latitude and longitude)");
        }
    }

    private static Set<String> codes(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(c -> !c.isEmpty())
                .map(c -> c.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    static JsonNode pageProps(JsonNode nextData) {
        JsonNode pp = nextData.path("props").path("pageProps");
        if (!pp.isObject()) {
            throw new SearchException("autotrader.ca: no page data");
        }
        return pp;
    }

    // ------------------------------------------------------------------ parsing

    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * A result: its id (the listing's UUID), address (the importer's canonical one, {@code /offers/x-{id}}: the site
     * redirects it to the listing), title, price (CAD, {@code price.priceRaw}), km ({@code tracking.mileage}) and
     * model group (the menu model; none for "Unspecified").
     */
    static FoundListing listing(JsonNode item) {
        String id = item.path("id").asText("").trim().toLowerCase(Locale.ROOT);
        if (!UUID.matcher(id).matches()) {
            return null;
        }
        String url = SITE + "/offers/x-" + id;
        JsonNode raw = item.path("price").path("priceRaw");
        BigDecimal price = raw.isNumber() ? raw.decimalValue() : null;
        String title = item.path("title").asText("").trim();
        String group = item.path("vehicle").path("modelGroup").asText("").trim();
        FoundListing l = new FoundListing(id, url, title.isEmpty() ? null : title, price, mileage(item));
        return group.isEmpty() || UNSPECIFIED.equalsIgnoreCase(group) ? l : l.withModel(group);
    }

    /** {@code tracking.mileage} ("131716"), else the shown "131,716 km"; null when neither. */
    static Integer mileage(JsonNode item) {
        for (String text : List.of(item.path("tracking").path("mileage").asText(""),
                item.path("vehicle").path("mileageInKm").asText(""))) {
            String digits = text.replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && digits.length() <= 7) {
                return Integer.valueOf(digits);
            }
        }
        return null;
    }

    /** {@code taxonomy.makesSorted}: [{"label":"BMW","value":13}, ...]. */
    static List<CatalogBrand> makes(JsonNode taxonomy) {
        List<CatalogBrand> out = new ArrayList<>();
        for (JsonNode m : taxonomy.path("makesSorted")) {
            String code = m.path("value").asText("");
            String name = m.path("label").asText("").trim();
            if (code.matches("\\d{1,9}") && !name.isEmpty()) {
                out.add(new CatalogBrand(code, name, null));
            }
        }
        return out;
    }

    /** {@code taxonomy.modelGroups[make]}: the make's model groups, "Unspecified" included. */
    static List<CatalogModel> groups(JsonNode taxonomy, String make) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode g : taxonomy.path("modelGroups").path(make)) {
            String code = g.path("value").asText("");
            String name = g.path("label").asText("").trim();
            if (code.matches("\\d{1,9}") && !name.isEmpty()) {
                out.add(new CatalogModel(code, name));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the query

    /**
     * One search: a make (its id), maybe a model group, model years, the fuel codes kept (null = all) and the
     * source's filters (null = none of them: all of Canada, any seller, as for the cars added by hand).
     */
    record Slice(String make, String group, int yearFrom, Integer yearTo, List<String> fuels,
                 AutotraderFilters filters) {

        Slice {
            if (make == null || !make.matches("\\d{1,9}")) {
                throw new SearchException("autotrader.ca: not a make id: " + make);
            }
            if (group != null && !group.matches("\\d{1,9}")) {
                throw new SearchException("autotrader.ca: not a model id: " + group);
            }
            fuels = fuels == null ? null : List.copyOf(fuels);
        }

        Slice withGroup(String g) {
            return new Slice(make, g, yearFrom, yearTo, fuels, filters);
        }

        Slice withYears(int from, int to) {
            return new Slice(make, group, from, to, fuels, filters);
        }

        Slice withFuels(List<String> codes) {
            return new Slice(make, group, yearFrom, yearTo, codes, filters);
        }

        /** The site's taxonomy id: "ma13", "ma13gr100038". */
        String cat() {
            return "ma" + make + (group == null ? "" : "gr" + group);
        }

        Map<String, String> query(int page, int size) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("atype", "C");
            q.put("cy", "CA");
            q.put("cat", cat());
            q.put("modelyearfrom", String.valueOf(yearFrom));
            if (yearTo != null) {
                q.put("modelyearto", String.valueOf(yearTo));
            }
            if (fuels != null) {
                q.put("fuel", String.join(",", fuels));
            }
            if (filters != null) {
                q.put("offer", filters.usedOnly() ? "U" : NOT_NEW);
                q.put("zip", filters.postalCode());
                q.put("zipr", String.valueOf(filters.radiusKm()));
                if (filters.latitude() != null && filters.longitude() != null) {
                    q.put("lat", String.valueOf(filters.latitude()));
                    q.put("lon", String.valueOf(filters.longitude()));
                }
                if (filters.excludePrivate()) {
                    q.put("custtype", "D");
                }
                q.put("damaged_listing", filters.excludeDamaged() ? "exclude" : "include");
                if (filters.excludeNoPrice()) {
                    q.put("pricefrom", MIN_PRICE_TEXT);
                }
            }
            q.put("sort", "age");
            q.put("desc", "1");
            q.put("size", String.valueOf(size));
            q.put("page", String.valueOf(page));
            return q;
        }

        URI uri(int page, int size) {
            return AutotraderSearch.uri("/cars", query(page, size));
        }
    }

    static URI uri(String path, Map<String, String> query) {
        String q = query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(SITE + path + (q.isEmpty() ? "" : "?" + q));
    }
}
