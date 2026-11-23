package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;

class GuaziSearchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SourceFilters NO_FILTERS = new SourceFilters(false, false, List.of());
    private static final Brand ZEEKR = new Brand("103934", "Zeekr", true, 2021,
            List.of(new CatalogModel("001", "001"), new CatalogModel("009", "009"), new CatalogModel("7X", "7X")));

    private static final class FakeGuazi implements GuaziSearch.Pages {

        final Map<String, Integer> listings = new HashMap<>(Map.of("001", 260, "009", 150, "7X", 120));
        final Map<String, AtomicInteger> calls = new HashMap<>();
        Predicate<String> emptyCount = s -> false;
        Predicate<String> shortPage = s -> false;

        @Override
        public JsonNode search(GuaziSearch.Slice slice, int page, int size, String recommendId) {
            String series = slice.seriesIds().size() == 1 ? slice.seriesIds().get(0) : "*";
            String key = series + ":" + page + ":" + size;
            int call = calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            int total = series.equals("*") ? listings.values().stream().mapToInt(Integer::intValue).sum()
                    : listings.getOrDefault(series, 0);
            ObjectNode data = JSON.createObjectNode();
            if (size == 1 && call == 1 && emptyCount.test(series)) {
                data.put("totalCount", 0);
                data.putArray("list");
                return data;
            }
            data.put("totalCount", total);
            ArrayNode list = data.putArray("list");
            int from = (page - 1) * size;
            int to = Math.min(from + size, total);
            if (size > 1 && call == 1 && shortPage.test(series) && page == 2) {
                to--;
            }
            for (int i = from; i < to; i++) {
                list.addObject().put("productId", series + "-" + i).put("seoUri", "zeekr-" + series + "-" + i + ".html");
            }
            return data;
        }
    }

    private static BrandPass read(FakeGuazi fake, List<FoundListing> found) {
        return new GuaziSearch(fake, 0).list(ZEEKR, NO_FILTERS, found::add);
    }

    @Test
    void testList_allModelsAnswer_readWhole() {
        FakeGuazi fake = new FakeGuazi();
        List<FoundListing> found = new ArrayList<>();

        BrandPass pass = read(fake, found);

        assertThat(pass.complete()).isTrue();
        assertThat(pass.found()).isEqualTo(530).isEqualTo(pass.expected());
        assertThat(pass.error()).isNull();
        assertThat(found).hasSize(530);
    }

    @Test
    void testList_modelCountedZeroOnce_readAgainAndWhole() {
        FakeGuazi fake = new FakeGuazi();
        fake.emptyCount = "009"::equals;
        List<FoundListing> found = new ArrayList<>();

        BrandPass pass = read(fake, found);

        assertThat(pass.complete()).isTrue();
        assertThat(pass.failed()).isFalse();
        assertThat(found).hasSize(530);
    }

    @Test
    void testList_shortPageInTheMiddle_readsOn() {
        FakeGuazi fake = new FakeGuazi();
        fake.shortPage = "001"::equals;
        List<FoundListing> found = new ArrayList<>();

        BrandPass pass = read(fake, found);

        assertThat(pass.complete()).isTrue();
        assertThat(found).hasSize(529);
        assertThat(fake.calls.get("001:9:30").get()).isEqualTo(1);
    }

    @Test
    void testList_modelGivesTooFewOnce_readAgainAndWhole() {
        FakeGuazi fake = new FakeGuazi();
        GuaziSearch.Pages tooFewOnce = (slice, page, size, rid) -> {
            JsonNode data = fake.search(slice, page, size, rid);
            if (size > 1 && slice.seriesIds().equals(List.of("009")) && page == 5
                    && fake.calls.get("009:5:30").get() == 1) {
                ((ObjectNode) data).set("list", JSON.createArrayNode());
            }
            return data;
        };
        List<FoundListing> found = new ArrayList<>();

        BrandPass pass = new GuaziSearch(tooFewOnce, 0).list(ZEEKR, NO_FILTERS, found::add);

        assertThat(pass.complete()).isTrue();
        assertThat(found).hasSize(530);
        assertThat(fake.calls.get("009:5:30").get()).isEqualTo(2);
    }

    @Test
    void testList_modelAlwaysCountedZero_failedWithShortModels() {
        FakeGuazi fake = new FakeGuazi();
        GuaziSearch.Pages alwaysEmpty = (slice, page, size, rid) -> slice.seriesIds().equals(List.of("001"))
                ? JSON.createObjectNode().put("totalCount", 0).set("list", JSON.createArrayNode())
                : fake.search(slice, page, size, rid);
        List<FoundListing> found = new ArrayList<>();

        BrandPass pass = new GuaziSearch(alwaysEmpty, 0).list(ZEEKR, NO_FILTERS, found::add);

        assertThat(pass.complete()).isFalse();
        assertThat(pass.failed()).isTrue();
        assertThat(pass.error()).startsWith("read 270 of 530");
        assertThat(found).hasSize(270);
    }

    @Test
    void testList_modelKeepsGivingTooFew_incompleteWithTheModelNamed() {
        FakeGuazi fake = new FakeGuazi();
        GuaziSearch.Pages tooFew = (slice, page, size, rid) -> {
            JsonNode data = fake.search(slice, page, size, rid);
            if (size > 1 && slice.seriesIds().equals(List.of("009")) && page == 5) {
                ((ObjectNode) data).set("list", JSON.createArrayNode());
            }
            return data;
        };
        List<FoundListing> found = new ArrayList<>();

        BrandPass pass = new GuaziSearch(tooFew, 0).list(ZEEKR, NO_FILTERS, found::add);

        assertThat(pass.complete()).isFalse();
        assertThat(pass.failed()).isFalse();
        assertThat(pass.error()).isEqualTo("read 500 of 530; short: 009 120/150");
    }
}
