package com.premiumcars.autoimport;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    public AutowiniSearch(ObjectMapper json, AppProperties props) {
        this.http = new SearchHttp(json, 350, Map.of(
                "User-Agent", props.importer().userAgent(),
                "Origin", "https://m.autowini.com",
                "Referer", "https://m.autowini.com/"));
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
            if (u.excluded().isEmpty()) {
                total += ok(http.get(uri("/items/cars/count", u.query()))).asInt();
            } else {
                total += fuelFacet(ok(http.get(uri("/items/cars/filters", u.query())))).stream()
                        .filter(f -> !excluded(u.excluded(), f.code(), f.name()))
                        .mapToInt(FuelOption::count).sum();
            }
        }
        return total;
    }

    @Override
    public BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found) {
        Set<String> seen = new HashSet<>();
        int expected = 0;
        try {
            for (Unit u : units(brand, filters)) {
                Map<String, String> q = u.query();
                boolean check = !u.excluded().isEmpty();
                boolean siteFilters = false;
                int keptCount = 0;
                if (check) {
                    List<FuelOption> kept = fuelFacet(ok(http.get(uri("/items/cars/filters", q)))).stream()
                            .filter(f -> !excluded(u.excluded(), f.code(), f.name())).toList();
                    if (kept.isEmpty()) {
                        continue; // every fuel type of it is left out
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
                int left = 0;
                int total = -1;
                for (int page = 1; page <= MAX_PAGES; page++) {
                    Map<String, String> p = new LinkedHashMap<>(q);
                    p.put("pageOffset", String.valueOf(page));
                    p.put("pageSize", String.valueOf(PAGE_SIZE));
                    JsonNode data = ok(http.get(uri("/items/cars", p)));
                    if (total < 0) {
                        total = data.path("totalCount").asInt();
                        expected += total;
                    }
                    JsonNode items = data.path("items");
                    for (JsonNode item : items) {
                        FoundListing l = listing(item);
                        if (l == null || !seen.add(l.id())) {
                            continue;
                        }
                        // each listing's own fuel type is checked too
                        if (check && excluded(u.excluded(), null, item.path("fuelType").asText(""))) {
                            left++;
                        } else {
                            unit.add(l);
                        }
                    }
                    if (items.size() < PAGE_SIZE || (long) page * PAGE_SIZE >= total) {
                        break;
                    }
                }
                if (check && !siteFilters && total > keptCount && left == 0) {
                    // the site ignored the filter and no listing's fuel type matched the left-out names: nothing of
                    // it is passed on rather than the fuel types left out
                    throw new SearchException("the fuel types left out could not be applied to " + brand.name()
                            + (q.containsKey("subModel") ? " (" + q.get("subModel") + ")" : ""));
                }
                unit.forEach(found);
            }
        } catch (SearchException e) {
            return new BrandPass(brand.code(), brand.name(), expected, seen.size(), false, e.getMessage());
        }
        // listings come and go while the pages are read: a few may slip between pages (the removal needs two
        // missed runs in a row, see AutoImportRunner)
        boolean complete = seen.size() >= expected * 0.98;
        return new BrandPass(brand.code(), brand.name(), expected, seen.size(), complete,
                complete ? null : "read " + seen.size() + " of " + expected);
    }

    /** One search: its query and the fuel types it leaves out. */
    record Unit(Map<String, String> query, List<String> excluded) {
    }

    /** One per chosen model (autowini's sub-model filter takes one) with its fuel types left out, or the make. */
    static List<Unit> units(Brand brand, SourceFilters filters) {
        Map<String, String> base = base(brand.code(), brand.yearFrom(), filters);
        if (brand.models().isEmpty()) {
            return List.of(new Unit(base, brand.excludedFuels()));
        }
        List<Unit> out = new ArrayList<>();
        for (CatalogModel m : brand.models()) {
            Map<String, String> q = new LinkedHashMap<>(base);
            q.put("subModel", m.code());
            out.add(new Unit(q, m.excludedFuels()));
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
        JsonNode km = item.path("mileage"); // when the answer has it; otherwise the import checks the page's
        return new FoundListing(id, AutowiniPageParser.desktopUrl(id), item.path("itemName").asText(null),
                price.isNumber() ? price.decimalValue() : null, km.isIntegralNumber() ? km.asInt() : null);
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
