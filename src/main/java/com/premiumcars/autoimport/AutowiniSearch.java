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

    @Override
    public int count(Brand brand, SourceFilters filters) {
        int total = 0;
        for (Map<String, String> q : queries(brand, filters)) {
            total += ok(http.get(uri("/items/cars/count", q))).asInt();
        }
        return total;
    }

    @Override
    public BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found) {
        Set<String> seen = new HashSet<>();
        int expected = 0;
        try {
            for (Map<String, String> q : queries(brand, filters)) {
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
                        // a listing without a price counts as read but is not imported
                        if (l != null && seen.add(l.id()) && l.price() != null && l.price().signum() > 0) {
                            found.accept(l);
                        }
                    }
                    if (items.size() < PAGE_SIZE || (long) page * PAGE_SIZE >= total) {
                        break;
                    }
                }
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

    /** One query per chosen model (autowini's sub-model filter takes one), or one for the whole make. */
    static List<Map<String, String>> queries(Brand brand, SourceFilters filters) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("make", brand.code());
        base.put("modelYearFrom", String.valueOf(brand.yearFrom()));
        if (filters.inspected()) {
            base.put("inspectionReportUploaded", "true");
        }
        if (filters.actualMileage()) {
            base.put("actualMileage", "true");
        }
        if (brand.models().isEmpty()) {
            return List.of(base);
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (CatalogModel m : brand.models()) {
            Map<String, String> q = new LinkedHashMap<>(base);
            q.put("subModel", m.code());
            out.add(q);
        }
        return out;
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
