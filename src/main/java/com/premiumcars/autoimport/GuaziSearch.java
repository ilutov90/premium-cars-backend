package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Year;
import java.time.ZoneId;
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
 * model with more year by year; the make's own total tells whether everything was read.
 */
@Component
public class GuaziSearch implements SourceSearch {

    static final String SITE = "https://en.guazi.com";
    static final int PAGE_SIZE = 30; // guazi's maximum
    static final int WINDOW = 500;
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    private final SearchHttp http;
    /** guazi wants a device id and a guid in every search; any value is accepted. */
    private final String deviceId = UUID.randomUUID().toString().replace("-", "");

    public GuaziSearch(ObjectMapper json, AppProperties props) {
        this.http = new SearchHttp(json, 500, Map.of(
                "User-Agent", props.importer().userAgent(),
                "Origin", SITE,
                "Referer", SITE + "/used-cars/"));
    }

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
    public BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found) {
        Set<String> seen = new HashSet<>();
        Consumer<FoundListing> once = l -> {
            if (seen.add(l.id())) {
                found.accept(l);
            }
        };
        int expected = 0;
        boolean complete = true;
        try {
            Slice whole = new Slice(brand, filters, ids(brand.models()), brand.yearFrom(), null);
            expected = total(whole);
            if (expected <= WINDOW) {
                read(whole, expected, once);
            } else {
                List<String> series = brand.models().isEmpty() ? ids(models(brand.code())) : ids(brand.models());
                int lastYear = Year.now(CHINA).getValue() + 1;
                for (String s : series) {
                    Slice model = new Slice(brand, filters, List.of(s), brand.yearFrom(), null);
                    int n = total(model);
                    if (n <= WINDOW) {
                        read(model, n, once);
                        continue;
                    }
                    for (int year = brand.yearFrom(); year <= lastYear; year++) {
                        Slice oneYear = new Slice(brand, filters, List.of(s), year, year);
                        int m = total(oneYear);
                        read(oneYear, m, once);
                        if (m > WINDOW) {
                            complete = false; // one model and one year with more than guazi shows
                        }
                    }
                }
            }
        } catch (SearchException e) {
            return new BrandPass(brand.code(), brand.name(), expected, seen.size(), false, e.getMessage());
        }
        complete = complete && seen.size() >= expected * 0.98;
        return new BrandPass(brand.code(), brand.name(), expected, seen.size(), complete,
                complete ? null : "read " + seen.size() + " of " + expected);
    }

    /** Auctions show guazi's stand-in price ("$9,999,999"): not imported, but still at the source. */
    @Override
    public boolean priced(FoundListing listing) {
        return GuaziPageParser.realPrice(listing.price());
    }

    /** One search: a make, maybe some models, a year range. */
    record Slice(Brand brand, SourceFilters filters, List<String> seriesIds, int yearFrom, Integer yearTo) {

        Map<String, Object> body(int pageNum, int pageSize, String deviceId) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("language", "en");
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
            return b;
        }
    }

    private int total(Slice slice) {
        return search(slice, 1, 1).path("totalCount").asInt();
    }

    /** Reads a slice's pages (at most the first {@value #WINDOW} results). */
    private void read(Slice slice, int total, Consumer<FoundListing> found) {
        int reachable = Math.min(total, WINDOW);
        for (int page = 1; (page - 1) * PAGE_SIZE < reachable; page++) {
            JsonNode list = search(slice, page, PAGE_SIZE).path("list");
            for (JsonNode item : list) {
                FoundListing l = listing(item);
                if (l != null) {
                    found.accept(l);
                }
            }
            if (list.size() < PAGE_SIZE) {
                break;
            }
        }
    }

    private JsonNode search(Slice slice, int page, int size) {
        return ok(http.post(URI.create(SITE + "/os/facade/search/product/list?language=en"),
                slice.body(page, size, deviceId)));
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
