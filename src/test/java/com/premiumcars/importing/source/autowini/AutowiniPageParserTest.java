package com.premiumcars.importing.source.autowini;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.source.autowini.AutowiniClient.Page;
import com.premiumcars.importing.source.autowini.AutowiniPageParser.ParsedPage;

class AutowiniPageParserTest {

    private static String page(String name) throws IOException {
        try (InputStream in = AutowiniPageParserTest.class.getResourceAsStream("/pages/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsListing() throws IOException {
        ParsedPage page = AutowiniPageParser.parse(page("autowini-IC4960158.html"), "IC4960158");
        CarSpecs specs = AutowiniPageParser.toSpecs(page);

        assertThat(page.itemId()).isEqualTo("IC4960158");
        assertThat(page.fields()).containsEntry("location", "S.Korea");
        assertThat(page.price()).isEqualByComparingTo("9250");
        assertThat(page.currency()).isEqualTo("USD");

        assertThat(specs.title()).isEqualTo("Audi Q7 (4M) 45 TDI Quattro Sport");
        assertThat(specs.make()).isEqualTo("Audi");
        assertThat(specs.model()).isEqualTo("Q7");
        assertThat(specs.trim()).isEqualTo("(4M) 45 TDI Quattro Sport");
        assertThat(specs.year()).isEqualTo(2016);
        assertThat(specs.mileageKm()).isEqualTo(258_850);
        assertThat(specs.engineCc()).isEqualTo(2967);
        assertThat(specs.fuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(specs.transmission()).isEqualTo(Transmission.AUTOMATIC);
        assertThat(specs.bodyType()).isEqualTo("SUV");
        assertThat(specs.color()).isEqualTo("Charcoal");
    }

    @Test
    void collectsLargePhotosOnce() throws IOException {
        ParsedPage page = AutowiniPageParser.parse(page("autowini-IC4960158.html"), "IC4960158");

        assertThat(page.images()).containsExactly(
                "https://imagebox.autowini.com/upload/U2021091190104/car/CI202604040004766237/7050ec7f-97d7-4524-8c47-9366bf201ef5_1024.jpg",
                "https://imagebox.autowini.com/upload/U2021091190104/car/CI202604040004766237/5361c6c9-3d70-4fd6-ab28-62a77e0c647f_1024.jpg",
                "https://imagebox.autowini.com/upload/U2021091190104/car/CI202604040004766237/0f3c2a9e-new-style-photo.jpeg");
        assertThat(AutowiniPageParser.imageCandidates(page.images().get(1))).hasSize(3)
                .last().asString().endsWith("5361c6c9-3d70-4fd6-ab28-62a77e0c647f.jpg");
    }

    /** A real page (fragments, see the file): the site menu repeats the labels before the listing's own. */
    @Test
    void readsTheRealDesktopPageWithItsHiddenPrice() throws IOException {
        ParsedPage page = AutowiniPageParser.parse(page("autowini-IC5354760.html"), "IC5354760");
        CarSpecs specs = AutowiniPageParser.toSpecs(page);

        assertThat(page.itemId()).isEqualTo("IC5354760");
        // the listing's values, not the menu's lists
        assertThat(page.fields()).containsEntry("make", "Hyundai")
                .containsEntry("fuel", "Gasoline")
                .containsEntry("vehicle_type", "SUV")
                .containsEntry("location", "S.Korea")
                // without the tooltip, whose "mileage" would turn the kilometres into miles
                .containsEntry("km", "116,710Km (Actual)");
        assertThat(specs.title()).isEqualTo("Hyundai SantaFe (MX5) Gasoline 2.5T 2WD Prestige");
        assertThat(specs.model()).isEqualTo("SantaFe");
        assertThat(specs.year()).isEqualTo(2024);
        assertThat(specs.mileageKm()).isEqualTo(116_710);
        assertThat(specs.engineCc()).isEqualTo(2497);
        assertThat(specs.fuelType()).isEqualTo(FuelType.PETROL);
        assertThat(specs.transmission()).isEqualTo(Transmission.AUTOMATIC);
        assertThat(specs.color()).isEqualTo("Black");
        assertThat(specs.firstRegistration()).isEqualTo("2023-09"); // "19th Sep. 2023", without the tooltip

        // the seller's options, as mobile.bg extras (LHD: no "Десен волан")
        assertThat(page.options()).containsExactly("Park Assist Sensor", "Full Auto Air Conditioner", "Heating Seat",
                "Power Seat", "Cooling Seat", "Cruise Control");
        assertThat(specs.features()).containsExactly(CarFeature.PARKING_SENSORS, CarFeature.VENTILATED_SEATS,
                CarFeature.POWER_SEATS, CarFeature.CLIMATE_CONTROL, CarFeature.HEATED_SEATS,
                CarFeature.CRUISE_CONTROL);

        // the BIS price autowini shows buyers: seller's 18,244 + BIS fee 912 + expenses 75 (USD, FOB Incheon)
        assertThat(page.price()).isEqualByComparingTo("19231");
        assertThat(page.currency()).isEqualTo("USD");
        assertThat(page.discount()).isNull();
    }

    @Test
    void firstRegistrationDates() {
        assertThat(AutowiniPageParser.yearMonth("19th Sep. 2023")).isEqualTo("2023-09");
        assertThat(AutowiniPageParser.yearMonth("1st January 2020")).isEqualTo("2020-01");
        assertThat(AutowiniPageParser.yearMonth("2021")).isEqualTo("2021");
        assertThat(AutowiniPageParser.yearMonth(" ")).isNull();
    }

    @Test
    void hiddenPriceAndDiscounts() {
        String html = """
                <ul class="infoTbl"><li><dl><dt>Item No.</dt><dd>IC1</dd></dl></li></ul>
                <input type="hidden" id="i_iPrice" value="10000">
                <input type="hidden" id="i_iBisPrice" value="10,575">
                <input type="hidden" id="i_iBisDiscount" value="0">
                <input type="hidden" id="i_iBisDiscountByEvent" value="200">
                <input type="hidden" id="i_iBisDiscountByMembership" value="50">
                """;
        ParsedPage page = AutowiniPageParser.parse(html, "IC1");
        assertThat(page.price()).isEqualByComparingTo("10575");
        assertThat(page.discount()).isEqualByComparingTo("250");

        // a member who may not see prices: empty or zero, then nothing (the admin enters the price)
        ParsedPage hidden = AutowiniPageParser.parse("""
                <dl><dt>Item No.</dt><dd>IC2</dd></dl>
                <input type="hidden" id="i_iBisPrice" value="">
                <input type="hidden" id="i_iBisDiscountByEvent" value="200">
                """, "IC2");
        assertThat(hidden.price()).isNull();
        assertThat(hidden.discount()).isNull();
        ParsedPage zero = AutowiniPageParser.parse("<input type=\"hidden\" id=\"i_iBisPrice\" value=\"0\">", "IC3");
        assertThat(zero.price()).isNull();
    }

    @Test
    void urls() {
        assertThat(AutowiniPageParser.itemId("https://www.autowini.com/Cars/ic4960158/cars-detail")).isEqualTo("IC4960158");
        assertThat(AutowiniPageParser.mobileUrl("IC4960158")).isEqualTo("https://m.autowini.com/items/IC4960158");
    }

    @Test
    void recognisesKeycloakPages() {
        assertThat(AutowiniClient.looksLikeKeycloak(new Page(200,
                "https://member-auth.tradewini.com/realms/tradewini/protocol/openid-connect/auth?client_id=x",
                "<form id=\"kc-form-login\" action=\"https://member-auth.tradewini.com/login-actions/authenticate\">")))
                .isTrue();
        assertThat(AutowiniClient.looksLikeKeycloak(new Page(200, "https://www.autowini.com/", "<html>home</html>")))
                .isFalse();
    }
}
