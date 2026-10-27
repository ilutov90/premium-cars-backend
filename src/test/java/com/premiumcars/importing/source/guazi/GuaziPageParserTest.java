package com.premiumcars.importing.source.guazi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Inspection;
import com.premiumcars.catalog.domain.SpecGroup;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.source.guazi.GuaziPageParser.ParsedPage;

/**
 * /pages/guazi-bxrayk2hvc.html holds the real {@code rawData} and JSON-LD of a saved en.guazi.com listing (Tesla
 * Model Y, October 2026) in the page's structure (Next.js flight chunks), without the masked VIN and without the
 * rest of the page (the saved page carried the logged-in visitor's account). /pages/guazi-68pfqu2abf.html is a
 * reduced copy with a subset of a listing's values (and a recommended car's rawData before it).
 */
class GuaziPageParserTest {

    private static String page(String name) throws IOException {
        try (InputStream in = GuaziPageParserTest.class.getResourceAsStream("/pages/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void urls() {
        assertThat(GuaziPageParser.productId(
                "https://en.guazi.com/products/tesla-model-y-2025-00l-gray-37900km-at-4wd-5-seats-bxrayk2hvc.html"))
                .isEqualTo("bxrayk2hvc");
        assertThat(GuaziPageParser.productId("https://en.guazi.com/cars?brand=byd")).isNull();
        assertThat(GuaziPageParser.englishUrl(URI.create(
                "https://ru.guazi.com/products/byd-song-max-2021-68pfqu2abf.html?utm_source=x#specs")))
                .isEqualTo(URI.create("https://en.guazi.com/products/byd-song-max-2021-68pfqu2abf.html"));
    }

    @Test
    void readsAnElectricCar() throws IOException {
        ParsedPage page = GuaziPageParser.parse(page("guazi-bxrayk2hvc.html"), "bxrayk2hvc");

        assertThat(page.productId()).isEqualTo("bxrayk2hvc");
        assertThat(page.title()).isEqualTo("Tesla Model Y 2025 Long-Range All-Wheel-Drive Version");
        assertThat(page.priceUsd()).isEqualByComparingTo("37899"); // Shanghai, the default port
        assertThat(page.batteryHealthPct()).isEqualTo(91);
        assertThat(page.fields()).containsEntry("location", "Shaoxing, China")
                .containsEntry("range", "719").containsEntry("range_label", "CLTC Electric Range (km)");
        assertThat(page.images()).hasSize(26) // exterior, interior and details; processed ("effect") photos kept
                .allMatch(u -> u.startsWith("https://global-image-pub.guazistatic-global.com/"));
        assertThat(page.inspection()).isEqualTo("guazi inspection: grade A; issues: Exterior Design 6, New Energy 1");

        // the report for the car page: the three checks passed, 7 areas, the 4 points with a defect (with video)
        Inspection report = page.report();
        assertThat(report.grade()).isEqualTo("A");
        assertThat(report.checks()).containsExactly(new Inspection.Check("No Accident Damage", true),
                new Inspection.Check("No Water Damage", true), new Inspection.Check("No Fire Damage", true));
        assertThat(report.categories()).hasSize(7)
                .contains(new Inspection.Category("Exterior Design", 92, 6), new Inspection.Category("New Energy", 11, 1));
        assertThat(report.findings()).hasSize(4).extracting(Inspection.Finding::item)
                .containsExactly("Directly ahead and downward", "Right side", "Directly behind and below", "Left side");
        assertThat(report.findings().get(2)).satisfies(f -> {
            assertThat(f.category()).isEqualTo("Exterior Design");
            assertThat(f.result()).isEqualTo("Minor defect");
            assertThat(f.note()).isEqualTo("Rear bumper surface damage.");
            assertThat(f.videoUrl()).startsWith("https://global-image-pub.guazistatic-global.com/").endsWith(".mp4");
        });

        CarSpecs s = GuaziPageParser.toSpecs(page);
        assertThat(s.make()).isEqualTo("Tesla");
        assertThat(s.model()).isEqualTo("Model Y");
        assertThat(s.trim()).isEqualTo("Long-Range All-Wheel-Drive Version");
        assertThat(s.year()).isEqualTo(2025);
        assertThat(s.firstRegistration()).isEqualTo("2025-04");
        assertThat(s.mileageKm()).isEqualTo(37_900);
        assertThat(s.fuelType()).isEqualTo(FuelType.ELECTRIC);
        assertThat(s.transmission()).isEqualTo(Transmission.AUTOMATIC);
        assertThat(s.drivetrain()).isEqualTo(Drivetrain.AWD);
        assertThat(s.batteryKwh()).isEqualByComparingTo("78.4");
        assertThat(s.electricRangeKm()).isEqualTo(719);
        assertThat(s.rangeStandard()).isEqualTo("CLTC");
        assertThat(s.motorPowerKw()).isEqualTo(331);
        assertThat(s.powerHp()).isEqualTo(450);
        assertThat(s.batteryHealthPct()).isEqualTo(91);
        assertThat(s.inspection()).isEqualTo(report);
        assertThat(s.engineCc()).isNull();
        assertThat(s.bodyType()).isEqualTo("SUV");
        assertThat(s.seats()).isEqualTo(5);
        assertThat(s.doors()).isEqualTo(5);
        assertThat(s.color()).isEqualTo("Dark grey");
        assertThat(s.curbWeightKg()).isEqualTo(1992);
        assertThat(s.dimensions()).isEqualTo("4797 × 1920 × 1624");
        assertThat(s.euroStandard()).isNull();
        assertThat(s.features()).contains(CarFeature.AIRBAGS_FRONT, CarFeature.AIRBAGS_SIDE, CarFeature.AIRBAGS_REAR,
                CarFeature.EBD, CarFeature.ESP, CarFeature.TRACTION_CONTROL, CarFeature.TIRE_PRESSURE_MONITORING,
                CarFeature.DISTANCE_CONTROL, CarFeature.CRUISE_CONTROL, CarFeature.CAMERA, CarFeature.USB_AUX,
                CarFeature.POWER_TAILGATE, CarFeature.KEYLESS_START, CarFeature.CENTRAL_LOCKING,
                CarFeature.LIGHT_SENSOR, CarFeature.RAIN_SENSOR, CarFeature.POWER_MIRRORS, CarFeature.POWER_WINDOWS,
                CarFeature.POWER_SEATS, CarFeature.MULTIFUNCTION_STEERING_WHEEL, CarFeature.HEATED_STEERING_WHEEL,
                CarFeature.ALLOY_WHEELS, CarFeature.PANORAMIC_ROOF, CarFeature.FOUR_BY_FOUR, CarFeature.DOORS_4_5)
                // "No" on the page: rear radar, front seat heating, on-board computer, paddle shifters
                .doesNotContain(CarFeature.PARKING_SENSORS, CarFeature.HEATED_SEATS, CarFeature.TRIP_COMPUTER,
                        CarFeature.MANUAL_SHIFT_MODE, CarFeature.SUNROOF, CarFeature.RIGHT_HAND_DRIVE,
                        CarFeature.SEVEN_SEATS);

        // the car page's sheet: "Yes" = fitted (""), "No" and "Optional" left out, values kept
        assertThat(s.sheet()).extracting(SpecGroup::name).containsExactly("Motor", "Body Structure",
                "Safety Configuration", "Driving Assistance Configuration", "Exterior Configuration",
                "Interior Configuration", "Seat Configuration", "Multimedia Configuration", "Lighting Configuration",
                "Glass/Mirrors");
        assertThat(s.sheet().get(2).items()).contains(new SpecGroup.Item("Rear Curtain Airbags", ""))
                .extracting(SpecGroup.Item::name).doesNotContain("Rear Side Airbags");
        assertThat(s.sheet().get(3).items()).extracting(SpecGroup.Item::name)
                .contains("Level of Driving Assistance", "Adaptive Cruise Control")
                .doesNotContain("Automatic Parking", "Rear Radar");
        assertThat(s.sheet().get(0).items()).contains(new SpecGroup.Item("Fast Charging", "Supported"));
    }

    @Test
    void readsACombustionCar() throws IOException {
        ParsedPage page = GuaziPageParser.parse(page("guazi-68pfqu2abf.html"), "68pfqu2abf");

        assertThat(page.priceUsd()).isEqualByComparingTo("7603"); // the current (discounted) price
        assertThat(page.batteryHealthPct()).isNull();
        assertThat(page.images()).hasSize(5);
        assertThat(page.inspection()).isEqualTo("guazi inspection: grade A; issues: Exterior 6");
        assertThat(page.report().categories()).containsExactly(new Inspection.Category("Exterior", 90, 6));
        assertThat(page.report().findings()).isEmpty();

        CarSpecs s = GuaziPageParser.toSpecs(page);
        assertThat(s.title()).isEqualTo("BYD Song MAX 2021 Upgraded Model 1.5T Automatic Luxury 6-Seater");
        assertThat(s.make()).isEqualTo("BYD");
        assertThat(s.model()).isEqualTo("Song MAX");
        assertThat(s.firstRegistration()).isEqualTo("2020-11");
        assertThat(s.mileageKm()).isEqualTo(103_700);
        assertThat(s.fuelType()).isEqualTo(FuelType.PETROL);
        assertThat(s.drivetrain()).isEqualTo(Drivetrain.FWD);
        assertThat(s.engine()).isEqualTo("1.5T");
        assertThat(s.engineCc()).isEqualTo(1500);
        assertThat(s.powerHp()).isEqualTo(160); // the engine's ps, not the kW
        assertThat(s.batteryKwh()).isNull();
        assertThat(s.batteryHealthPct()).isNull();
        assertThat(s.bodyType()).isEqualTo("Mini Van");
        assertThat(s.seats()).isEqualTo(6);
        assertThat(s.curbWeightKg()).isNull();
        assertThat(s.features()).contains(CarFeature.AIR_CONDITIONING, CarFeature.CENTRAL_LOCKING, CarFeature.CAMERA,
                        CarFeature.KEYLESS_START, CarFeature.PARKING_SENSORS, CarFeature.POWER_WINDOWS,
                        CarFeature.CRUISE_CONTROL, CarFeature.START_STOP, CarFeature.ROOF_RAILS, CarFeature.DOORS_4_5)
                .doesNotContain(CarFeature.AIR_SUSPENSION, CarFeature.SEVEN_SEATS, CarFeature.FOUR_BY_FOUR);
    }

    @Test
    void aPageWithoutListingDataIsRefused() {
        assertThatThrownBy(() -> GuaziPageParser.parse("<html><body>Not found</body></html>", "abcdef1234"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batteryHealthFormats() throws IOException {
        ObjectMapper json = new ObjectMapper();
        assertThat(GuaziPageParser.batteryHealth(json.readTree("\"91%\""))).isEqualTo(91);
        assertThat(GuaziPageParser.batteryHealth(json.readTree("0.9"))).isEqualTo(90);
        assertThat(GuaziPageParser.batteryHealth(json.readTree("{\"soh\":\"88\"}"))).isEqualTo(88);
        assertThat(GuaziPageParser.batteryHealth(json.readTree("null"))).isNull();
        assertThat(GuaziPageParser.batteryHealth(json.readTree("\"-\""))).isNull();
    }

    @Test
    void imageCandidatesTryTheOriginalFirst() {
        assertThat(GuaziPageParser.imageCandidates("https://x.guazistatic-global.com/a.jpg?x-bce-process=image/format,f_jpg"))
                .containsExactly("https://x.guazistatic-global.com/a.jpg",
                        "https://x.guazistatic-global.com/a.jpg?x-bce-process=image/format,f_jpg");
    }

    @Test
    void rawDataMayBeAReferenceToAnotherFlightRow() {
        String row5 = "5:[\\\"$\\\",\\\"div\\\",null,{\\\"rawData\\\":\\\"$1f\\\"}]\\n";
        String row1f = "1f:{\\\"productId\\\":\\\"abcdef12\\\",\\\"title\\\":\\\"Used BYD Han 2022\\\","
                + "\\\"prices\\\":[{\\\"price\\\":\\\"$$9,000\\\",\\\"isDefault\\\":1}]}\\n";
        String html = "<script>self.__next_f.push([1,\"" + row5 + row1f + "\"])</script>";

        ParsedPage page = GuaziPageParser.parse(html, "abcdef12");
        assertThat(page.title()).isEqualTo("BYD Han 2022");
        assertThat(page.priceUsd()).isEqualByComparingTo("9000");
    }

    @Test
    void aBotCheckPageIsDescribed() {
        assertThatThrownBy(() -> GuaziPageParser.parse(
                "<html><head><title>Check</title><script>var t='EO_Bot_Ssid=';</script></head></html>", "abcdef12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title \"Check\"")
                .hasMessageContaining("Next.js data missing");
    }
}
