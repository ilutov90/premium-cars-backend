package com.premiumcars.importing.source.autohome;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.extract.EvSpecsExtractor;
import com.premiumcars.importing.extract.EvSpecsExtractor.EvSpecs;
import com.premiumcars.importing.source.autohome.AutohomePageParser.ParsedPage;

class AutohomePageParserTest {

    private static String page(String name) throws IOException {
        try (InputStream in = AutohomePageParserTest.class.getResourceAsStream("/pages/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsVehicleDetails() throws IOException {
        ParsedPage page = AutohomePageParser.parse(page("autohome-60182282.html"), "60182282");

        assertThat(page.title()).isEqualTo("BYD Han 2020 EV Extended Range Premium");
        assertThat(page.priceUsd()).isEqualByComparingTo("12020");
        assertThat(page.galleryTotal()).isEqualTo(24);
        assertThat(page.fields()).containsEntry("location", "xian").containsEntry("seats", "5");
        assertThat(page.fuelType()).isEqualTo(FuelType.ELECTRIC);

        CarSpecs specs = AutohomePageParser.toSpecs(page, EvSpecs.NONE);
        assertThat(specs.make()).isEqualTo("BYD");
        assertThat(specs.model()).isEqualTo("Han");
        assertThat(specs.trim()).isEqualTo("EV Extended Range Premium");
        assertThat(specs.year()).isEqualTo(2020);
        assertThat(specs.firstRegistration()).isEqualTo("2020-10");
        assertThat(specs.mileageKm()).isEqualTo(102_300);
        assertThat(specs.transmission()).isEqualTo(Transmission.AUTOMATIC);
        assertThat(specs.drivetrain()).isEqualTo(Drivetrain.FWD);
        assertThat(specs.engineCc()).isNull(); // "--"
        assertThat(specs.bodyType()).isEqualTo("Sedan");
        assertThat(specs.seats()).isEqualTo(5);
        assertThat(specs.doors()).isEqualTo(4);
        assertThat(specs.color()).isEqualTo("Red");
        assertThat(specs.curbWeightKg()).isEqualTo(2020);
        assertThat(specs.dimensions()).isEqualTo("4980 × 1910 × 1495");
        assertThat(specs.description()).isNull(); // the source's SEO text is not imported
    }

    @Test
    void collectsPhotosOfThisListingOnly() throws IOException {
        ParsedPage page = AutohomePageParser.parse(page("autohome-60182282.html"), "60182282");

        // og:image first, the escaped Next.js URL of the same photo is a duplicate, the second photo is new,
        // the "similar car" photo is excluded.
        assertThat(page.images()).hasSize(2);
        assertThat(page.images().getFirst()).contains("ChxpVWrN5DOAeS9eAAFvWo4GpSo018");
        assertThat(page.images().get(1)).contains("SECONDPHOTO002");
        assertThat(page.images()).noneMatch(u -> u.contains("OTHERCAR001"));
    }

    @Test
    void prefersTheLargePhoto() {
        List<String> c = AutohomePageParser.imageCandidates(
                "https://x.autoimg.cn/escimg/auto/g33/M0B/01/02/400x300_c42_autohomecar__SECONDPHOTO002.jpg.webp?w=720");
        assertThat(c.getFirst())
                .isEqualTo("https://x.autoimg.cn/escimg/auto/g33/M0B/01/02/1400x0_c42_autohomecar__SECONDPHOTO002.jpg");
        assertThat(c).contains(
                "https://x.autoimg.cn/escimg/auto/g33/M0B/01/02/400x300_c42_autohomecar__SECONDPHOTO002.jpg.webp?w=720");
    }

    @Test
    void addsElectricDataFromTheBrowserTabs() throws IOException {
        ParsedPage page = AutohomePageParser.parse(page("autohome-60182282.html"), "60182282");
        // what the browser reads after clicking "Electric Motor" and "Battery && Charging"
        EvSpecs ev = EvSpecsExtractor.extract(List.of(List.of(
                "Electric Motor", "Total motor power (kW)", "163", "Total motor horsepower (Ps)", "222",
                "Battery && Charging", "Battery capacity (kWh)", "76.9", "Battery warranty", "8 years",
                "NEDC pure electric range (km)", "605")));

        CarSpecs specs = AutohomePageParser.toSpecs(page, ev);

        assertThat(specs.powerHp()).isEqualTo(222);
        assertThat(specs.motorPowerKw()).isEqualTo(163);
        assertThat(specs.batteryKwh()).isEqualByComparingTo("76.9");
        assertThat(specs.electricRangeKm()).isEqualTo(605);
        assertThat(specs.rangeStandard()).isEqualTo("NEDC");
    }

    @Test
    void canonicalUrls() {
        assertThat(AutohomePageParser.englishUrl("https://global.autohome.com.cn/detail/60182282"))
                .isEqualTo("https://global.autohome.com.cn/en/detail/60182282");
        assertThat(AutohomePageParser.listingId("https://global.che168.com/detail/60182282?session_id=x"))
                .isEqualTo("60182282");
    }
}
