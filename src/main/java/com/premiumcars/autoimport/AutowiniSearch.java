package com.premiumcars.autoimport;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.FuelOption;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.source.autowini.AutowiniPageParser;

/**
 * autowini's search through the API its mobile site uses (v2api.autowini.com, no login):
 * {@code GET /items/cars?make=C0810&modelYearFrom=2018&inspectionReportUploaded=true&actualMileage=true
 * &pageOffset=1&pageSize=40} ({@code data.totalCount}, {@code data.items[]}), {@code /items/cars/count} and
 * {@code /items/cars/filters} (makes; with {@code make=...} its sub-models). Models are autowini's "sub-models"
 * (Carnival, K5, ...). Prices are FOB in USD, as the importer reads them anyway.
 */
@Component
public class AutowiniSearch implements SourceSearch {

    static final String API = "https://v2api.autowini.com";
    static final int PAGE_SIZE = 40; // autowini's maximum
    private static final int MAX_PAGES = 3000;

    private final SearchHttp http;

    public AutowiniSearch(ObjectMapper json, AppProperties props, AutoImportInstance instance,
                          com.premiumcars.importing.browser.BrowserFetch browser) {
        // since Nov 2026 the API answers browsers only (HTTP 403 to the server): then asked from autowini's
        // mobile site in the headless browser, as that site's own pages ask it
        this.http = new SearchHttp(json, instance.searchPause().toMillis(), Map.of(
                "User-Agent", props.importer().userAgent(),
                "Origin", "https://m.autowini.com",
                "Referer", "https://m.autowini.com/"), browser, "https://m.autowini.com/");
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOWINI;
    }

    @Override
    public List<CatalogBrand> brands() {
        List<CatalogBrand> out = new ArrayList<>();
        for (JsonNode m : ok(http.get(uri("/items/cars/filters", Map.of()))).path("carMake")) {
            out.add(new CatalogBrand(m.path("code").asText(), m.path("name").asText(), null));
        }
        return out;
    }

    @Override
    public List<CatalogModel> models(String brandCode) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode m : ok(http.get(uri("/items/cars/filters", Map.of("make", brandCode)))).path("carSubModel")) {
            out.add(new CatalogModel(m.path("code").asText(), m.path("name").asText()));
        }
        return out;
    }

    /** A make's or model's fuel types: the "fuelType" facet of {@code /items/cars/filters} under the filters. */
    @Override
    public List<FuelOption> fuels(String brandCode, String modelCode, int yearFrom, SourceFilters filters) {
        Map<String, String> q = base(brandCode, yearFrom, filters);
        if (modelCode != null && !modelCode.isBlank()) {
            q.put("subModel", modelCode);
        }
        return fuelFacet(ok(http.get(uri("/items/cars/filters", q))));
    }

    static List<FuelOption> fuelFacet(JsonNode data) {
        List<FuelOption> out = new ArrayList<>();
        for (JsonNode f : data.path("fuelType")) {
            String name = f.path("name").asText(f.path("code").asText(""));
            if (!name.isBlank()) {
                out.add(new FuelOption(f.path("code").asText(name), name, f.path("count").asInt()));
            }
        }
        return out;
    }

    @Override
    public int count(Brand brand, SourceFilters filters) {
        int total = 0;
        for (Unit u : units(brand, filters)) {
            total += count(u);
        }
        return total;
    }

    /** One search's number of listings, its left-out fuel types not counted. */
    private int count(Unit u) {
        if (u.excluded().isEmpty()) {
            return ok(http.get(uri("/items/cars/count", u.query()))).asInt();
        }
        return fuelFacet(ok(http.get(uri("/items/cars/filters", u.query())))).stream()
                .filter(f -> !excluded(u.excluded(), f.code(), f.name()))
                .mapToInt(FuelOption::count).sum();
    }

    /**
     * Reads the make's listings, each with its model in autowini's own menu (the search results' "subModelName", so
     * the car's model is named after it, not after the listing page's "All New 5 Series (G30)"). The whole make is
     * read and its listings kept by the chosen models and fuel types; only when the chosen models are few enough
     * that their own searches take fewer pages are they read model by model.
     */
    @Override
    public BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found) {
        Set<String> seen = new HashSet<>();
        int expected = 0;
        try {
            Map<String, String> base = base(brand.code(), brand.yearFrom(), filters);
            boolean fuels = !brand.excludedFuels().isEmpty()
                    || brand.models().stream().anyMatch(m -> !m.excludedFuels().isEmpty());
            JsonNode facets = brand.models().isEmpty() && !fuels ? null
                    : ok(http.get(uri("/items/cars/filters", base)));
            int wholeCount = ok(http.get(uri("/items/cars/count", base))).asInt();
            if (!brand.models().isEmpty() && !wholeCheaper(brand.models(), facets.path("carSubModel"), wholeCount)) {
                for (Unit u : units(brand, filters)) {
                    expected += read(brand, u, seen, found);
                }
            } else {
                expected = Math.max(readWhole(brand, base, facets == null ? null : facets.path("fuelType"), seen,
                        found), wholeCount);
            }
        } catch (SearchException e) {
            return new BrandPass(brand.code(), brand.name(), expected, seen.size(), false, e.getMessage(), true);
        }
        // listings come and go while the pages are read: a few may slip between pages
        boolean complete = seen.size() >= expected * 0.98;
        return new BrandPass(brand.code(), brand.name(), expected, seen.size(), complete,
                complete ? null : "read " + seen.size() + " of " + expected, false);
    }

    /**
     * Reading the whole make takes no more pages than the chosen models' own searches (their numbers from the
     * make's "carSubModel" list; each search takes one page at least, and a model with fuel types left out two
     * requests more).
     */
    static boolean wholeCheaper(List<CatalogModel> chosen, JsonNode menu, int wholeCount) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode m : menu) {
            counts.put(m.path("code").asText(""), m.path("count").asInt());
        }
        long byModel = 0;
        for (CatalogModel m : chosen) {
            byModel += Math.max(1, pages(counts.getOrDefault(m.code(), 0))) + (m.excludedFuels().isEmpty() ? 0 : 2);
        }
        return pages(wholeCount) <= byModel;
    }

    private static int pages(int listings) {
        return (listings + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    /**
     * Reads the whole make (no model, no fuel type in the query), each listing once, and passes on those of the
     * chosen models (all when none is chosen) whose fuel type is not left out, with their menu model.
     *
     * @param fuelFacet the make's "fuelType" list (its codes are checked too), or null
     * @return the search's total
     */
    private int readWhole(Brand brand, Map<String, String> query, JsonNode fuelFacet, Set<String> seen,
                          Consumer<FoundListing> found) {
        Map<String, CatalogModel> chosen = new HashMap<>();
        brand.models().forEach(m -> chosen.put(fuelKey(m.name()), m));
        List<String> makeLeftOut = withCodes(brand.excludedFuels(), fuelFacet);
        Map<String, List<String>> modelLeftOut = new HashMap<>();
        List<FoundListing> kept = new ArrayList<>();
        int total = pages(query, item -> {
            FoundListing l = listing(item);
            if (l == null || !seen.add(l.id())) {
                return;
            }
            String model = item.path("subModelName").asText("").trim();
            List<String> leftOut = makeLeftOut;
            if (!chosen.isEmpty()) {
                CatalogModel m = chosen.get(fuelKey(model));
                if (m == null) {
                    return; // a model not chosen
                }
                model = m.name();
                leftOut = modelLeftOut.computeIfAbsent(m.code(), c -> withCodes(m.excludedFuels(), fuelFacet));
            }
            if (!leftOut.isEmpty() && excluded(leftOut, null, item.path("fuelType").asText(""))) {
                return;
            }
            kept.add(model.isEmpty() ? l : l.withModel(model));
        });
        kept.forEach(found);
        return total;
    }

    /** The fuel types left out by their names and by their codes in the facet ("Electric Vehicle (EV)" = "EV"). */
    static List<String> withCodes(List<String> names, JsonNode fuelFacet) {
        if (names.isEmpty() || fuelFacet == null) {
            return names;
        }
        List<String> out = new ArrayList<>(names);
        for (JsonNode f : fuelFacet) {
            String name = f.path("name").asText(f.path("code").asText(""));
            String code = f.path("code").asText("");
            if (!code.isBlank() && excluded(names, null, name)) {
                out.add(code);
            }
        }
        return out;
    }

    /**
     * The listing is in autowini's search: {@code /items/cars?keyword=IC5489163} (one result, the listing; none for
     * a listing taken off the search or one that never was).
     */
    @Override
    public Boolean listed(String listingId) {
        JsonNode data = ok(http.get(uri("/items/cars", Map.of("keyword", listingId))));
        JsonNode items = data.path("items");
        if (!items.isArray()) {
            throw new SearchException("autowini: no items in the answer for " + listingId);
        }
        for (JsonNode item : items) {
            if (listingId.equalsIgnoreCase(item.path("listingId").asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one search's pages up to its total: each listing to {@code item}. A page without its total (autowini
     * leaves it out at times) takes it from the count; an empty page before the end is asked once more; a short
     * page does not end the pages (listings that went while the pages were read shift them).
     *
     * @return the search's total
     */
    private int pages(Map<String, String> q, Consumer<JsonNode> item) {
        int total = -1;
        boolean retried = false;
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, String> p = new LinkedHashMap<>(q);
            p.put("pageOffset", String.valueOf(page));
            p.put("pageSize", String.valueOf(PAGE_SIZE));
            JsonNode data = ok(http.get(uri("/items/cars", p)));
            total = Math.max(total, totalCount(data));
            if (total < 0) {
                total = ok(http.get(uri("/items/cars/count", q))).asInt();
            }
            JsonNode items = data.path("items");
            if (items.isEmpty() && !retried && (long) (page - 1) * PAGE_SIZE < total) {
                retried = true;
                page--;
                continue;
            }
            retried = false;
            items.forEach(item);
            if (lastPage(items.size(), page, total)) {
                break;
            }
        }
        return Math.max(total, 0);
    }

    /**
     * Reads one search's pages, each listing once (with the search's menu model), its left-out fuel types left out.
     *
     * @return the search's total (its count)
     */
    private int read(Brand brand, Unit u, Set<String> seen, Consumer<FoundListing> found) {
        Map<String, String> q = u.query();
        boolean check = !u.excluded().isEmpty();
        boolean siteFilters = false;
        int keptCount = 0;
        if (check) {
            List<FuelOption> kept = fuelFacet(ok(http.get(uri("/items/cars/filters", q)))).stream()
                    .filter(f -> !excluded(u.excluded(), f.code(), f.name())).toList();
            if (kept.isEmpty()) {
                return 0; // every fuel type of it is left out
            }
            keptCount = kept.stream().mapToInt(FuelOption::count).sum();
            // the site filters by the kept fuel types when it takes the filter: its count says so
            Map<String, String> withFuels = withFuels(q, kept);
            siteFilters = ok(http.get(uri("/items/cars/count", withFuels))).asInt() == keptCount;
            if (siteFilters) {
                q = withFuels;
            }
        }
        List<FoundListing> unit = new ArrayList<>();
        int[] left = {0};
        boolean checkEach = check;
        int total = pages(q, item -> {
            FoundListing l = listing(item);
            if (l == null || !seen.add(l.id())) {
                return;
            }
            // each listing's own fuel type is checked too
            if (checkEach && excluded(u.excluded(), null, item.path("fuelType").asText(""))) {
                left[0]++;
            } else {
                unit.add(u.model() == null ? l : l.withModel(u.model()));
            }
        });
        if (check && !siteFilters && total > keptCount && left[0] == 0) {
            // the site ignored the filter and no listing's fuel type matched the left-out names: nothing of it is
            // passed on rather than the fuel types left out
            throw new SearchException("the fuel types left out could not be applied to " + brand.name()
                    + (q.containsKey("subModel") ? " (" + q.get("subModel") + ")" : ""));
        }
        unit.forEach(found);
        return total;
    }

    /** The answer's {@code totalCount}; -1 when the answer has none. */
    static int totalCount(JsonNode data) {
        JsonNode t = data.path("totalCount");
        return t.isNumber() || (t.isTextual() && t.asText().matches("\\d{1,9}")) ? t.asInt() : -1;
    }

    /**
     * The search's pages end here: an empty page, or the total read. A short page before the total does not end
     * them (listings that went while the pages were read shift the pages; the next ones still hold listings).
     */
    static boolean lastPage(int items, int page, int total) {
        return items == 0 || (long) page * PAGE_SIZE >= total;
    }

    /** One search: its query, the fuel types it leaves out and its menu model (null: the whole make). */
    record Unit(Map<String, String> query, List<String> excluded, String model) {
    }

    /** One per chosen model (autowini's sub-model filter takes one) with its fuel types left out, or the make. */
    static List<Unit> units(Brand brand, SourceFilters filters) {
        Map<String, String> base = base(brand.code(), brand.yearFrom(), filters);
        if (brand.models().isEmpty()) {
            return List.of(new Unit(base, brand.excludedFuels(), null));
        }
        List<Unit> out = new ArrayList<>();
        for (CatalogModel m : brand.models()) {
            Map<String, String> q = new LinkedHashMap<>(base);
            q.put("subModel", m.code());
            out.add(new Unit(q, m.excludedFuels(), m.name()));
        }
        return out;
    }

    static List<Map<String, String>> queries(Brand brand, SourceFilters filters) {
        return units(brand, filters).stream().map(Unit::query).toList();
    }

    private static Map<String, String> base(String make, int yearFrom, SourceFilters filters) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("make", make);
        base.put("modelYearFrom", String.valueOf(yearFrom));
        if (filters.inspected()) {
            base.put("inspectionReportUploaded", "true");
        }
        if (filters.actualMileage()) {
            base.put("actualMileage", "true");
        }
        return base;
    }

    /** The query for these fuel types only (autowini's list filters take several values joined by commas). */
    static Map<String, String> withFuels(Map<String, String> query, List<FuelOption> fuels) {
        Map<String, String> q = new LinkedHashMap<>(query);
        q.put("fuelType", fuels.stream().map(FuelOption::code).collect(Collectors.joining(",")));
        return q;
    }

    /** The fuel type (by its code or its name) is one of those left out; names compared without case and spaces. */
    static boolean excluded(List<String> excluded, String code, String name) {
        for (String e : excluded) {
            String k = fuelKey(e);
            if (!k.isEmpty() && (k.equals(fuelKey(code)) || k.equals(fuelKey(name)))) {
                return true;
            }
        }
        return false;
    }

    static String fuelKey(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+]", "");
    }

    static FoundListing listing(JsonNode item) {
        String id = item.path("listingId").asText("");
        if (id.isBlank()) {
            return null;
        }
        JsonNode price = item.path("price");
        return new FoundListing(id, AutowiniPageParser.desktopUrl(id), item.path("itemName").asText(null),
                price.isNumber() ? price.decimalValue() : null, mileage(item));
    }

    private static final java.util.regex.Pattern CONTENT_KM =
            java.util.regex.Pattern.compile("([0-9][0-9,.]*)\\s*km\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The km from the search result, so a listing over the limit is set aside without opening its page: the
     * {@code mileage} field, else the summary line ("IC5577815 · Gasoline · … · 34,099km (Actual)"); null when
     * neither has it (the import checks the page).
     */
    static Integer mileage(JsonNode item) {
        JsonNode km = item.path("mileage");
        if (km.isIntegralNumber()) {
            return km.asInt();
        }
        if (km.isTextual() && km.asText().replaceAll("[^0-9]", "").length() > 0
                && km.asText().replaceAll("[^0-9]", "").length() <= 7) {
            return Integer.valueOf(km.asText().replaceAll("[^0-9]", ""));
        }
        java.util.regex.Matcher m = CONTENT_KM.matcher(item.path("content").asText(""));
        Integer found = null;
        while (m.find()) {
            String digits = m.group(1).replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && digits.length() <= 7) {
                found = Integer.valueOf(digits); // the last "…km" of the line is the mileage
            }
        }
        return found;
    }

    /** {@code data} of a {"result":"SUCCESS","data":...} answer. */
    static JsonNode ok(JsonNode answer) {
        if (!"SUCCESS".equals(answer.path("result").asText())) {
            throw new SearchException("autowini: " + answer.path("result").asText("no result") + " "
                    + answer.path("errorMessage").asText(""));
        }
        return answer.path("data");
    }

    static URI uri(String path, Map<String, String> query) {
        String q = query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(API + path + (q.isEmpty() ? "" : "?" + q));
    }
}
