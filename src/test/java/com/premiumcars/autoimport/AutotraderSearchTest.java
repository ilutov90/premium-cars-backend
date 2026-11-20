package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.premiumcars.autoimport.AutoImportTypes.AutotraderFilters;
import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.autoimport.AutotraderSearch.Slice;
import com.premiumcars.autoimport.SourceSearch.SearchException;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.common.BadRequestException;

class AutotraderSearchTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode samplePage() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/search/autotrader-search-bmw.html"));
        return AutotraderSearch.pageProps(new SearchHttp(json, 0, Map.of()).parsePage("www.autotrader.ca", 200, html));
    }

    private static SourceFilters filters(AutotraderFilters a) {
        return new SourceFilters(false, false, List.of(), a);
    }

    @Test
    void testPage_SampleResultsPage_ReadsTotalAndListings() throws Exception {
        AutotraderSearch.Page page = AutotraderSearch.page(samplePage());
        assertThat(page.total()).isEqualTo(3893);
        assertThat(page.items()).hasSize(12);
    }

    @Test
    void testListing_SampleResult_ReadsIdUrlPriceKmAndModelGroup() throws Exception {
        FoundListing l = AutotraderSearch.listing(AutotraderSearch.page(samplePage()).items().getFirst());
        assertThat(l.id()).isEqualTo("9dea6722-70f5-49df-b189-a719429ff9ed");
        assertThat(l.url()).isEqualTo("https://www.autotrader.ca/offers/x-9dea6722-70f5-49df-b189-a719429ff9ed");
        assertThat(l.title()).isEqualTo("2019 BMW 8 Series Coupe M850i");
        assertThat(l.price()).isEqualByComparingTo("61900");
        assertThat(l.mileageKm()).isEqualTo(20746);
        assertThat(l.model()).isEqualTo("8 Series");
    }

    @Test
    void testPriced_CallForPriceStandIn_IsNotPriced() throws Exception {
        AutotraderSearch search = new AutotraderSearch(u -> null);
        List<FoundListing> all = AutotraderSearch.page(samplePage()).items().stream().map(AutotraderSearch::listing)
                .toList();
        FoundListing standIn = all.stream().filter(l -> l.id().startsWith("9e1509ba")).findFirst().orElseThrow();
        assertThat(search.priced(standIn)).isFalse();
        assertThat(search.priced(all.getFirst())).isTrue();
        assertThat(search.currency()).isEqualTo("CAD");
    }

    @Test
    void testMakes_SampleTaxonomy_ListsMakesWithIds() throws Exception {
        List<CatalogBrand> makes = AutotraderSearch.makes(samplePage().path("taxonomy"));
        assertThat(makes).hasSizeGreaterThan(100);
        assertThat(makes).contains(new CatalogBrand("13", "BMW", null));
    }

    @Test
    void testModels_SampleTaxonomy_ListsModelGroupsWithoutUnspecified() throws Exception {
        JsonNode page = json.createObjectNode().set("props", json.createObjectNode().set("pageProps", samplePage()));
        List<URI> asked = new ArrayList<>();
        AutotraderSearch search = new AutotraderSearch(u -> {
            asked.add(u);
            return page;
        });
        List<CatalogModel> models = search.models("13");
        assertThat(models).contains(new CatalogModel("100038", "3 Series"), new CatalogModel("18387", "X3"));
        assertThat(models).extracting(CatalogModel::name).doesNotContain("Unspecified");
        assertThat(AutotraderSearch.groups(samplePage().path("taxonomy"), "13")).extracting(CatalogModel::name)
                .contains("Unspecified");
        assertThat(asked.getFirst().getQuery()).contains("cat=ma13");
    }

    @Test
    void testQuery_DefaultFilters_SendsTheSitesParameters() {
        Slice s = new Slice("13", "100038", 2018, null, null, AutotraderFilters.DEFAULTS);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("atype", "C");
        expected.put("cy", "CA");
        expected.put("cat", "ma13gr100038");
        expected.put("modelyearfrom", "2018");
        expected.put("offer", "U");
        expected.put("zip", "N5P1C5");
        expected.put("zipr", "400");
        expected.put("lat", "42.779213");
        expected.put("lon", "-81.192156");
        expected.put("custtype", "D");
        expected.put("damaged_listing", "exclude");
        expected.put("pricefrom", "100");
        expected.put("sort", "age");
        expected.put("desc", "1");
        expected.put("size", "100");
        expected.put("page", "2");
        assertThat(s.query(2, 100)).containsExactlyEntriesOf(expected);
    }

    @Test
    void testQuery_FiltersSwitchedOffWithLocation_SendsOtherConditionsAndCoordinates() {
        AutotraderFilters a = new AutotraderFilters("m5v 2t6", 100, 43.64, -79.39, false, false, false, false);
        Map<String, String> q = new Slice("13", null, 2020, 2021, List.of("B", "2"), a).query(1, 20);
        assertThat(q).containsEntry("cat", "ma13").containsEntry("modelyearto", "2021").containsEntry("fuel", "B,2")
                .containsEntry("offer", "U,D,J,O,S").containsEntry("zip", "M5V2T6").containsEntry("zipr", "100")
                .containsEntry("lat", "43.64").containsEntry("lon", "-79.39")
                .containsEntry("damaged_listing", "include")
                .doesNotContainKeys("custtype", "pricefrom");
    }

    @Test
    void testQuery_NoFilters_SearchesAllOfCanada() {
        Map<String, String> q = new Slice("13", null, 2015, null, null, null).query(1, 20);
        assertThat(q).doesNotContainKeys("zip", "zipr", "custtype", "offer", "damaged_listing", "pricefrom");
    }

    @Test
    void testSlice_MakeIdNotANumber_IsRefused() {
        assertThatThrownBy(() -> new Slice("13|x", null, 2018, null, null, null)).isInstanceOf(SearchException.class);
    }

    @Test
    void testCheck_SiteSearchedAnotherCat_Throws() throws Exception {
        JsonNode pp = samplePage();
        Slice s = new Slice("13", "100038", 2018, null, null, null);
        assertThatThrownBy(() -> AutotraderSearch.check(s, pp, List.of())).isInstanceOf(SearchException.class)
                .hasMessageContaining("ma13gr100038");
    }

    @Test
    void testCheck_ResultsWithoutDistance_Throws() throws Exception {
        JsonNode pp = samplePage();
        ObjectNode item = json.createObjectNode();
        item.set("location", json.createObjectNode().put("city", "EDMONTON"));
        Slice s = new Slice("13", null, 2018, null, null, AutotraderFilters.DEFAULTS);
        assertThatThrownBy(() -> AutotraderSearch.check(s, pp, List.of(item))).isInstanceOf(SearchException.class)
                .hasMessageContaining("latitude");
    }

    @Test
    void testCheck_SampleWithItsOwnFilters_Passes() throws Exception {
        JsonNode pp = samplePage();
        Slice s = new Slice("13", null, 2018, null, null, AutotraderFilters.DEFAULTS);
        AutotraderSearch.check(s, pp, AutotraderSearch.page(pp).items());
    }

    @Test
    void testInSlice_ResultOfAnotherMakeOrModel_IsLeftOut() throws Exception {
        JsonNode item = AutotraderSearch.page(samplePage()).items().getFirst();
        assertThat(AutotraderSearch.inSlice(new Slice("13", "100042", 2018, null, null, null), item)).isTrue();
        assertThat(AutotraderSearch.inSlice(new Slice("13", "100038", 2018, null, null, null), item)).isFalse();
        assertThat(AutotraderSearch.inSlice(new Slice("9", null, 2018, null, null, null), item)).isFalse();
        assertThat(AutotraderSearch.inSlice(new Slice("13", null, 2018, null, List.of("E"), null), item)).isFalse();
    }

    @Test
    void testKept_ElectricLeftOutByName_SearchesTheOtherCodes() {
        assertThat(AutotraderSearch.kept(List.of("Electric"))).containsExactly("B", "2", "D", "F", "C", "L", "O");
        assertThat(AutotraderSearch.kept(List.of())).isNull();
    }

    @Test
    void testListedIn_ActiveListingPage_IsListed() throws Exception {
        JsonNode pp = json.readTree("""
                {"listingDetails":{"id":"566d07ca-26da-496c-a36d-15acf524bdbf","status":"Active"}}""");
        assertThat(AutotraderSearch.listedIn(pp, "566d07ca-26da-496c-a36d-15acf524bdbf")).isTrue();
    }

    @Test
    void testListedIn_RedirectedToResults_IsGone() throws Exception {
        assertThat(AutotraderSearch.listedIn(samplePage(), "566d07ca-26da-496c-a36d-15acf524bdbf")).isFalse();
    }

    @Test
    void testListed_NotAUuid_Throws() {
        AutotraderSearch search = new AutotraderSearch(u -> null);
        assertThatThrownBy(() -> search.listed("../cars")).isInstanceOf(SearchException.class);
    }

    @Test
    void testParsePage_PageWithoutData_IsRefused() {
        SearchHttp http = new SearchHttp(json, 0, Map.of());
        assertThatThrownBy(() -> http.parsePage("www.autotrader.ca", 200,
                "<html><head><title>Access denied</title></head></html>"))
                .isInstanceOf(SearchHttp.Refused.class).hasMessageContaining("Access denied");
    }

    @Test
    void testList_MakeOverTheWindow_ReadsByModelGroupAndYear() {
        FakeSite site = new FakeSite();
        site.add("1", 2019, 3000);
        site.add("2", 2020, 3900);
        site.add("2", 2021, 1100);
        site.add("3", 2022, 200);
        List<FoundListing> found = new ArrayList<>();
        BrandPass p = new AutotraderSearch(site).list(new Brand("13", "BMW", true, 2018, List.of()),
                filters(AutotraderFilters.DEFAULTS), found::add);
        assertThat(p.expected()).isEqualTo(8200);
        assertThat(p.found()).isEqualTo(8200);
        assertThat(p.complete()).isTrue();
        assertThat(found).extracting(FoundListing::id).doesNotHaveDuplicates();
        assertThat(site.asked).anyMatch(q -> "ma13gr2".equals(q.get("cat")) && "2020".equals(q.get("modelyearto")));
    }

    @Test
    void testList_OneModelYearOverTheWindow_IsNotComplete() {
        FakeSite site = new FakeSite();
        site.add("2", 2020, 4500);
        BrandPass p = new AutotraderSearch(site).list(new Brand("13", "BMW", true, 2018, List.of()),
                filters(AutotraderFilters.DEFAULTS), l -> { });
        assertThat(p.found()).isEqualTo(4000);
        assertThat(p.complete()).isFalse();
        assertThat(p.failed()).isFalse();
    }

    @Test
    void testList_ChosenModelWithFuelLeftOut_SearchesItsKeptFuelsWithItsName() {
        FakeSite site = new FakeSite();
        site.add("1", 2019, 30);
        List<FoundListing> found = new ArrayList<>();
        BrandPass p = new AutotraderSearch(site).list(new Brand("13", "BMW", true, 2018,
                List.of(new CatalogModel("1", "3 Series", List.of("Electric")))), filters(AutotraderFilters.DEFAULTS),
                found::add);
        assertThat(p.complete()).isTrue();
        assertThat(found).hasSize(30).allMatch(l -> "3 Series".equals(l.model()));
        assertThat(site.asked.getFirst()).containsEntry("cat", "ma13gr1").containsEntry("fuel", "B,2,D,F,C,L,O");
    }

    @Test
    void testCount_EveryFuelLeftOut_CountsNothingWithoutAsking() {
        FakeSite site = new FakeSite();
        site.add("1", 2019, 30);
        int n = new AutotraderSearch(site).count(new Brand("13", "BMW", true, 2018, List.of(), List.copyOf(
                AutotraderSearch.FUELS.values())), filters(AutotraderFilters.DEFAULTS));
        assertThat(n).isZero();
        assertThat(site.asked).isEmpty();
    }

    @Test
    void testCheckFilters_BadPostalCode_IsRefused() {
        SourceFilters f = filters(new AutotraderFilters("12345", 100, null, null, null, null, null, null));
        assertThatThrownBy(() -> AutoImportService.check(CarSource.AUTOTRADER_CA, f))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void testCheckFilters_RadiusOutOfRange_IsRefused() {
        SourceFilters f = filters(new AutotraderFilters("N5P1C5", 500, null, null, null, null, null, null));
        assertThatThrownBy(() -> AutoImportService.check(CarSource.AUTOTRADER_CA, f))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void testCheckFilters_OnlyLatitude_IsRefused() {
        SourceFilters f = filters(new AutotraderFilters("N5P1C5", 100, 42.7, null, null, null, null, null));
        assertThatThrownBy(() -> AutoImportService.check(CarSource.AUTOTRADER_CA, f))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void testCheckFilters_NoneGiven_TakesTheDefaults() {
        SourceFilters f = AutoImportService.check(CarSource.AUTOTRADER_CA, new SourceFilters(false, false, List.of()));
        assertThat(f.autotrader()).isEqualTo(new AutotraderFilters("N5P1C5", 400, null, null, true, true, true, true));
    }

    @Test
    void testCheckFilters_OtherSource_DropsAutotradersFilters() {
        SourceFilters f = AutoImportService.check(CarSource.AUTOWINI, filters(AutotraderFilters.DEFAULTS));
        assertThat(f.autotrader()).isNull();
    }

    @Test
    void testSourceFilters_SavedEmpty_ReadsWithoutAutotrader() throws Exception {
        SourceFilters f = json.readValue("{}", SourceFilters.class);
        assertThat(f.autotrader()).isNull();
        assertThat(f.autotraderOrDefaults().radiusKm()).isEqualTo(400);
        SourceFilters partial = json.readValue("{\"autotrader\":{\"radiusKm\":150}}", SourceFilters.class);
        assertThat(partial.autotrader().radiusKm()).isEqualTo(150);
        assertThat(partial.autotrader().excludePrivate()).isTrue();
        assertThat(json.writeValueAsString(new SourceFilters(true, true, List.of()))).doesNotContain("autotrader");
    }

    @Test
    void testCurrency_Autotrader_IsCad() {
        assertThat(PriceLimit.currency(CarSource.AUTOTRADER_CA)).isEqualTo("CAD");
        assertThat(PriceLimit.currency(CarSource.GUAZI)).isEqualTo("USD");
    }

    /** Made-up results pages: listings of make 13 by model group and year, the site's window and paging. */
    private final class FakeSite implements Function<URI, JsonNode> {
        record Row(String id, String group, int year) {
        }

        final List<Row> rows = new ArrayList<>();
        final List<Map<String, String>> asked = new ArrayList<>();

        void add(String group, int year, int n) {
            for (int i = 0; i < n; i++) {
                rows.add(new Row(String.format("%08x-0000-0000-0000-%012x", rows.size(), rows.size()), group, year));
            }
        }

        @Override
        public JsonNode apply(URI uri) {
            Map<String, String> q = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                String[] kv = part.split("=", 2);
                q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            asked.add(q);
            String cat = q.get("cat");
            String group = cat.contains("gr") ? cat.substring(cat.indexOf("gr") + 2) : null;
            int from = Integer.parseInt(q.get("modelyearfrom"));
            int to = q.containsKey("modelyearto") ? Integer.parseInt(q.get("modelyearto")) : 9999;
            List<Row> match = rows.stream().filter(r -> group == null || r.group().equals(group))
                    .filter(r -> r.year() >= from && r.year() <= to).toList();
            int size = Integer.parseInt(q.get("size"));
            int page = Integer.parseInt(q.get("page"));
            ObjectNode pp = json.createObjectNode();
            pp.put("numberOfResults", match.size());
            ObjectNode pageQuery = pp.putObject("pageQuery").put("cat", cat);
            if (q.containsKey("fuel")) {
                pageQuery.put("fuel", q.get("fuel"));
            }
            ArrayNode listings = pp.putArray("listings");
            int start = (page - 1) * size;
            for (int i = start; i < Math.min(match.size(), Math.min(start + size, AutotraderSearch.WINDOW)); i++) {
                Row r = match.get(i);
                ObjectNode item = listings.addObject();
                item.put("id", r.id());
                item.put("url", AutotraderSearch.SITE + "/offers/bmw-x-" + r.id());
                item.putObject("price").put("priceRaw", new BigDecimal("20000"));
                item.putObject("vehicle").put("modelGroup", "G" + r.group()).put("offerType", "U");
                item.putObject("location").put("distanceToSearchLocationInKm", 50);
                item.putObject("tracking").put("fuelType", "b").put("mileage", "1000")
                        .put("modelTaxonomy", "[make_id:13, model_group_id:" + r.group() + ", variant_id:1];");
            }
            ArrayNode groups = pp.putObject("taxonomy").putObject("modelGroups").putArray("13");
            for (String g : List.of("1", "2", "3")) {
                groups.addObject().put("value", Integer.parseInt(g)).put("label", g.equals("3") ? "Unspecified" : "G" + g);
            }
            ObjectNode root = json.createObjectNode();
            root.putObject("props").set("pageProps", pp);
            return root;
        }
    }
}
