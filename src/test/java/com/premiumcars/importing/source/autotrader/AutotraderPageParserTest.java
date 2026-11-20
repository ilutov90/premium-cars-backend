package com.premiumcars.importing.source.autotrader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.SpecGroup;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.ListingGoneException;
import com.premiumcars.importing.source.autotrader.AutotraderPageParser.ParsedPage;

class AutotraderPageParserTest {

    static final String BMW = "566d07ca-26da-496c-a36d-15acf524bdbf";
    static final String AUDI = "a7d69528-2a98-4063-865f-a98732ede635";

    static String page(String id) throws IOException {
        try (InputStream in = AutotraderPageParserTest.class.getResourceAsStream("/pages/autotrader-" + id + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void testListingId_BothUrlForms_ReturnsLowerCaseUuid() {
        assertThat(AutotraderPageParser.listingId("https://www.autotrader.ca/offers/bmw-x3-xdrive30i-gasoline-"
                + "cat_ma13gr18387va3241mt341tr485311-" + BMW.toUpperCase() + "?x=1")).isEqualTo(BMW);
        assertThat(AutotraderPageParser.listingId("https://www.autotrader.ca/offers/x-" + BMW)).isEqualTo(BMW);
        assertThat(AutotraderPageParser.listingId("https://www.autotrader.ca/cars/bmw/")).isNull();
    }

    @Test
    void testOriginalImage_SizeSuffixAndPromoQuery_AreRemoved() {
        String base = "https://prod.pictures.autoscout24.net/listing-images/a_b.jpg";
        assertThat(AutotraderPageParser.originalImage(base + "/1280x960.webp")).isEqualTo(base);
        assertThat(AutotraderPageParser.originalImage(base + "/250x188.webp?promo=x_10")).isEqualTo(base);
        assertThat(AutotraderPageParser.originalImage(base)).isEqualTo(base);
    }

    @Test
    void testParse_DealerBmwX3_ReadsFieldsAndPrice() throws IOException {
        ParsedPage p = AutotraderPageParser.parse(page(BMW), BMW);
        CarSpecs s = p.specs();

        assertThat(p.id()).isEqualTo(BMW);
        assertThat(s.title()).isEqualTo("2018 BMW X3 xDrive30i");
        assertThat(s.make()).isEqualTo("BMW");
        assertThat(s.model()).isEqualTo("X3");
        assertThat(s.trim()).isEqualTo("xDrive30i");
        assertThat(s.year()).isEqualTo(2018);
        assertThat(s.mileageKm()).isEqualTo(131716);
        assertThat(s.fuelType()).isEqualTo(FuelType.PETROL);
        assertThat(s.transmission()).isEqualTo(Transmission.AUTOMATIC);
        assertThat(s.drivetrain()).isEqualTo(Drivetrain.AWD);
        assertThat(s.engineCc()).isEqualTo(2000);
        assertThat(s.engine()).isEqualTo("2.0 L 4 cyl.");
        assertThat(s.bodyType()).isEqualTo("SUV");
        assertThat(s.doors()).isEqualTo(4);
        assertThat(s.powerHp()).isNull();
        assertThat(s.color()).isNull();
        assertThat(s.description()).isNull();
        assertThat(p.priceCad()).isEqualByComparingTo(new BigDecimal("19910"));
        assertThat(p.location()).isEqualTo("Edmonton, AB");
        assertThat(p.listedOn()).isEqualTo("2026-11-18");
    }

    @Test
    void testParse_DealerBmwX3_ReadsPhotosWithoutSizeSuffix() throws IOException {
        ParsedPage p = AutotraderPageParser.parse(page(BMW), BMW);

        assertThat(p.images()).hasSize(35).doesNotHaveDuplicates();
        assertThat(p.images().get(0)).isEqualTo("https://prod.pictures.autoscout24.net/listing-images/" + BMW
                + "_cf283e65-88af-4f25-8fe4-60f66e481f8b.jpg");
        assertThat(p.images()).allSatisfy(u -> assertThat(u).endsWith(".jpg"));
    }

    @Test
    void testParse_DealerBmwX3_MapsEquipmentToFeaturesAndSheet() throws IOException {
        CarSpecs s = AutotraderPageParser.parse(page(BMW), BMW).specs();

        assertThat(s.features()).containsExactlyInAnyOrder(CarFeature.CLIMATE_CONTROL,
                CarFeature.HEATED_STEERING_WHEEL, CarFeature.TRIP_COMPUTER, CarFeature.ALARM,
                CarFeature.FOUR_BY_FOUR, CarFeature.DOORS_4_5);
        assertThat(s.sheet()).extracting(SpecGroup::name)
                .containsExactly("Vehicle", "Comfort & Convenience", "Entertainment & Media", "Safety & Security");
        SpecGroup comfort = s.sheet().get(1);
        assertThat(comfort.items()).extracting(SpecGroup.Item::name).containsExactly(
                "Automatic Climate Control, Dual-Zone", "Heated steering wheel", "Leather steering wheel");
        assertThat(comfort.items()).extracting(SpecGroup.Item::value).containsOnly("");
    }

    @Test
    void testParse_AudiEtronGt_ReadsElectricCarAndPower() throws IOException {
        ParsedPage p = AutotraderPageParser.parse(page(AUDI), AUDI);
        CarSpecs s = p.specs();

        assertThat(s.title()).isEqualTo("2023 Audi e-tron GT");
        assertThat(s.make()).isEqualTo("Audi");
        assertThat(s.model()).isEqualTo("e-tron GT");
        assertThat(s.year()).isEqualTo(2023);
        assertThat(s.mileageKm()).isEqualTo(58197);
        assertThat(s.fuelType()).isEqualTo(FuelType.ELECTRIC);
        assertThat(s.powerHp()).isEqualTo(463);
        assertThat(s.motorPowerKw()).isEqualTo(345);
        assertThat(s.engineCc()).isNull();
        assertThat(s.engine()).isNull();
        assertThat(s.drivetrain()).isEqualTo(Drivetrain.AWD);
        assertThat(s.bodyType()).isEqualTo("Sedan");
        assertThat(s.seats()).isEqualTo(5);
        assertThat(s.color()).isEqualTo("Grey");
        assertThat(s.batteryKwh()).isNull();
        assertThat(s.electricRangeKm()).isNull();
        assertThat(p.priceCad()).isEqualByComparingTo(new BigDecimal("69995"));
        assertThat(p.location()).isEqualTo("Toronto, ON");
        assertThat(p.images()).hasSize(43);
        assertThat(p.images().get(0)).endsWith("_4c1df960-4267-4ce8-82d6-494c51273b79.jpg");
    }

    @Test
    void testParse_AudiEtronGt_KeepsEquipmentListOutOfTrimAndTakesFeaturesFromDescription() throws IOException {
        CarSpecs s = AutotraderPageParser.parse(page(AUDI), AUDI).specs();

        assertThat(s.trim()).isNull();
        assertThat(s.description()).isNull();
        assertThat(s.sheet()).extracting(SpecGroup::name).containsExactly("Vehicle");
        assertThat(s.sheet().get(0).items()).extracting(SpecGroup.Item::name)
                .contains("Interior colour", "Gears", "Seller's version note");
        assertThat(s.features()).contains(CarFeature.FOUR_BY_FOUR, CarFeature.PANORAMIC_ROOF,
                CarFeature.HEATED_SEATS, CarFeature.HEATED_STEERING_WHEEL, CarFeature.NAVIGATION,
                CarFeature.PARKING_SENSORS, CarFeature.RAIN_SENSOR, CarFeature.BLUETOOTH, CarFeature.USB_AUX,
                CarFeature.POWER_TAILGATE, CarFeature.POWER_MIRRORS, CarFeature.VENTILATED_SEATS,
                CarFeature.DOORS_4_5);
    }

    @Test
    void testParse_PriceOne_MeansCallForPrice() throws IOException {
        String html = page(BMW).replace("\"priceRaw\":19910", "\"priceRaw\":1");

        ParsedPage p = AutotraderPageParser.parse(html, BMW);

        assertThat(p.priceCad()).isNull();
        assertThat(p.specs().title()).isEqualTo("2018 BMW X3 xDrive30i");
    }

    @Test
    void testParse_PriceOnRequest_IsNoPrice() throws IOException {
        String html = page(BMW).replaceFirst("\"onRequestOnly\":false", "\"onRequestOnly\":true");

        assertThat(AutotraderPageParser.parse(html, BMW).priceCad()).isNull();
    }

    @Test
    void testParse_PriceOfHundred_IsARealPrice() throws IOException {
        String html = page(BMW).replace("\"priceRaw\":19910", "\"priceRaw\":100");

        assertThat(AutotraderPageParser.parse(html, BMW).priceCad()).isEqualByComparingTo("100");
    }

    @Test
    void testFuel_HybridCodes_AreHybridOrPlugIn() throws IOException {
        String hybrid = page(BMW).replace("\"fuelCategory\":{\"raw\":\"B\",\"formatted\":\"Gasoline\"}",
                "\"fuelCategory\":{\"raw\":\"2\",\"formatted\":\"Gas/Electric Hybrid\"}");
        String plugIn = page(BMW).replace("\"fuelCategory\":{\"raw\":\"B\",\"formatted\":\"Gasoline\"}",
                "\"fuelCategory\":{\"raw\":\"2\",\"formatted\":\"Plug-in Hybrid\"}");
        String diesel = page(BMW).replace("\"fuelCategory\":{\"raw\":\"B\",\"formatted\":\"Gasoline\"}",
                "\"fuelCategory\":{\"raw\":\"D\",\"formatted\":\"Diesel\"}");
        String gas = page(BMW).replace("\"fuelCategory\":{\"raw\":\"B\",\"formatted\":\"Gasoline\"}",
                "\"fuelCategory\":{\"raw\":\"C\",\"formatted\":\"Natural Gas\"}");

        assertThat(AutotraderPageParser.parse(hybrid, BMW).specs().fuelType()).isEqualTo(FuelType.HYBRID);
        assertThat(AutotraderPageParser.parse(plugIn, BMW).specs().fuelType()).isEqualTo(FuelType.PLUGIN_HYBRID);
        assertThat(AutotraderPageParser.parse(diesel, BMW).specs().fuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(AutotraderPageParser.parse(gas, BMW).specs().fuelType()).isEqualTo(FuelType.OTHER);
    }

    @Test
    void testParse_ResultsPageWithoutListingDetails_IsGone() {
        String results = "<html><head><title>Audi e-tron GT</title></head><body><script id=\"__NEXT_DATA__\" "
                + "type=\"application/json\">{\"props\":{\"pageProps\":{\"numberOfResults\":12,\"listings\":[]}}}"
                + "</script></body></html>";

        assertThat(AutotraderPageParser.hasListing(results)).isFalse();
        assertThatThrownBy(() -> AutotraderPageParser.parse(results, AUDI)).isInstanceOf(ListingGoneException.class);
    }

    @Test
    void testParse_StatusNotActive_IsGone() throws IOException {
        String html = page(BMW).replace("\"status\":\"Active\"", "\"status\":\"Sold\"");

        assertThatThrownBy(() -> AutotraderPageParser.parse(html, BMW)).isInstanceOf(ListingGoneException.class)
                .hasMessageContaining("Sold");
    }

    @Test
    void testParse_PageWithoutNextData_IsNotReadable() {
        assertThatThrownBy(() -> AutotraderPageParser.parse("<html><body>Access denied</body></html>", BMW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("__NEXT_DATA__");
    }

    @Test
    void testParse_PageOfAnotherListing_IsRefused() throws IOException {
        assertThatThrownBy(() -> AutotraderPageParser.parse(page(BMW), AUDI))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(BMW);
    }

    @Test
    void testCanonicalUrl_Id_IsTheShortLink() {
        assertThat(AutotraderPageParser.canonicalUrl(BMW))
                .isEqualTo(URI.create("https://www.autotrader.ca/offers/x-" + BMW));
    }
}
