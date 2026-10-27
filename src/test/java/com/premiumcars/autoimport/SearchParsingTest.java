package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.autoimport.SourceSearch.SearchException;

/** The search requests and answers of both sites, shaped as the investigation of October 2026 saw them. */
class SearchParsingTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode tree(String text) throws Exception {
        return json.readTree(text);
    }

    // ------------------------------------------------------------------ autowini

    @Test
    void autowiniQueries() {
        Brand kia = new Brand("C0810", "Kia", true, 2018, List.of());
        assertThat(AutowiniSearch.queries(kia, new SourceFilters(true, true, List.of()))).containsExactly(Map.of(
                "make", "C0810", "modelYearFrom", "2018", "inspectionReportUploaded", "true", "actualMileage", "true"));
        assertThat(AutowiniSearch.queries(kia, new SourceFilters(false, false, List.of("S"))))
                .containsExactly(Map.of("make", "C0810", "modelYearFrom", "2018"));

        Brand twoModels = new Brand("C0810", "Kia", true, 2020,
                List.of(new CatalogModel("C1150", "Carnival"), new CatalogModel("C1200", "K5")));
        assertThat(AutowiniSearch.queries(twoModels, new SourceFilters(true, false, List.of())))
                .extracting(q -> q.get("subModel")).containsExactly("C1150", "C1200");

        assertThat(AutowiniSearch.uri("/items/cars", new java.util.LinkedHashMap<>(Map.of("make", "C0810"))).toString())
                .isEqualTo("https://v2api.autowini.com/items/cars?make=C0810");
    }

    @Test
    void autowiniItem() throws Exception {
        FoundListing l = AutowiniSearch.listing(tree("""
                {"code":"CI202610130005388121","listingId":"IC5577815","detailUrl":"/items/Used-2020-Kia-K3-IC5577815",
                 "itemName":"2020 Kia All NEW K3 1.6 A/T","price":8354,"odometerCheck":true}"""));
        assertThat(l.id()).isEqualTo("IC5577815");
        assertThat(l.url()).isEqualTo("https://www.autowini.com/Cars/IC5577815/cars-detail"); // as the importer keeps it
        assertThat(l.title()).isEqualTo("2020 Kia All NEW K3 1.6 A/T");
        assertThat(l.price()).isEqualByComparingTo("8354");
        assertThat(AutowiniSearch.listing(tree("{\"itemName\":\"x\"}"))).isNull();
    }

    @Test
    void autowiniErrors() throws Exception {
        assertThat(AutowiniSearch.ok(tree("{\"result\":\"SUCCESS\",\"data\":8049}")).asInt()).isEqualTo(8049);
        assertThatThrownBy(() -> AutowiniSearch.ok(tree(
                "{\"result\":\"FAIL\",\"errorMessage\":\"pageSize: must be less than or equal to 40\"}")))
                .isInstanceOf(SearchException.class).hasMessageContaining("pageSize");
    }

    // ------------------------------------------------------------------ guazi

    @Test
    void guaziSearchBody() {
        Brand byd = new Brand("1212", "BYD", true, 2022, List.of());
        var body = new GuaziSearch.Slice(byd, new SourceFilters(false, false, List.of("S", "A", "B")), List.of("162513"),
                2023, 2023).body(2, 30, "dev");
        assertThat(body).containsEntry("brandId", "1212").containsEntry("seriesIds", List.of("162513"))
                .containsEntry("detectionLevels", List.of("S", "A", "B")).containsEntry("mfgDateStart", 2023)
                .containsEntry("mfgDateEnd", 2023).containsEntry("businessType", 5).containsEntry("did", "dev")
                .containsEntry("guid", "dev").containsEntry("pageNum", 2).containsEntry("pageSize", 30);

        var whole = new GuaziSearch.Slice(byd, new SourceFilters(false, false, List.of()), List.of(), 2022, null)
                .body(1, 1, "dev");
        assertThat(whole).doesNotContainKeys("seriesIds", "detectionLevels", "mfgDateEnd")
                .containsEntry("mfgDateStart", 2022);
    }

    @Test
    void guaziItem() throws Exception {
        FoundListing l = GuaziSearch.listing(tree("""
                {"productId":"hhsub43ux6","title":"Used BYD Song PLUS New Energy 2021 EV Flagship Model",
                 "seoUri":"byd-song-plus-new-energy-2021-00l-gray-36200km-at-2wd-5-seats-hhsub43ux6.html",
                 "price":"$15,588","mfgDate":"2022.12","labels":[{"name":"Grade S","type":2}]}"""));
        assertThat(l.id()).isEqualTo("hhsub43ux6");
        assertThat(l.url()).isEqualTo(
                "https://en.guazi.com/products/byd-song-plus-new-energy-2021-00l-gray-36200km-at-2wd-5-seats-hhsub43ux6.html");
        assertThat(l.title()).isEqualTo("BYD Song PLUS New Energy 2021 EV Flagship Model");
        assertThat(l.price()).isEqualByComparingTo("15588");
        assertThat(GuaziSearch.listing(tree("{\"productId\":\"x\"}"))).isNull();
        assertThat(GuaziSearch.price("")).isNull();
    }

    @Test
    void guaziMakesAndErrors() throws Exception {
        List<CatalogBrand> brands = GuaziSearch.brandList(GuaziSearch.ok(tree("""
                {"code":0,"message":"成功","data":{"brands":{
                  "A":[{"id":"103885","name":"AION","tags":[{"id":"164033","name":"i60"}]}],
                  "B":[{"id":"1212","name":"BYD","tags":[{"id":"162513","name":"Song PLUS New Energy"},
                                                         {"id":"162750","name":"Seal"}]}]}}}""")));
        assertThat(brands).extracting(CatalogBrand::code).containsExactly("103885", "1212");
        assertThat(brands.get(1).models()).containsExactly(new CatalogModel("162513", "Song PLUS New Energy"),
                new CatalogModel("162750", "Seal"));

        assertThatThrownBy(() -> GuaziSearch.ok(tree("{\"code\":400,\"message\":\"每页数超过最大查询阈值\"}")))
                .isInstanceOf(SearchException.class).hasMessageContaining("400");
    }
}
