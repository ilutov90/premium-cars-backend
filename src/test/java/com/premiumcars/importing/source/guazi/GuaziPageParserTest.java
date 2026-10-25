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
import com.premiumcars.catalog.domain.SpecGroup;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.source.guazi.GuaziPageParser.ParsedPage;

/**
 * The pages under /pages/guazi-*.html are reduced copies of two real en.guazi.com listings: the same structure
 * (Next.js flight chunks holding {@code rawData}, the JSON-LD Car) with a subset of the values, without VIN or
 * seller data.
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

        assertThat(page.productId()).isEqualTo("bxrayk2hvc"); // not the recommended car before it
        assertThat(page.title()).isEqualTo("Tesla Model Y 2025 Long-Range All-Wheel-Drive Version");
        assertThat(page.priceUsd()).isEqualByComparingTo("37899"); // the default port (Shanghai)
        assertThat(page.batteryHealthPct()).isEqualTo(91);
        assertThat(page.fields()).containsEntry("location", "Shaoxing, China")
                .containsEntry("range", "719").containsEntry("range_label", "CLTC Electric Range (km)");
        assertThat(page.images()).hasSize(3) // the duplicate photo once; processed ("effect") photos kept
                .first().asString().startsWith("https://global-image-pub.guazistatic-global.com/a1.jpg");
        assertThat(page.inspection()).isEqualTo("guazi inspection: grade A; issues: Exterior 6, New Energy 1");

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
        assertThat(s.engineCc()).isNull();
        assertThat(s.bodyType()).isEqualTo("SUV");
        assertThat(s.color()).isEqualTo("Dark grey");
        assertThat(s.curbWeightKg()).isEqualTo(1992);
        assertThat(s.dimensions()).isEqualTo("4797 × 1920 × 1624");
        assertThat(s.euroStandard()).isNull();
        assertThat(s.features()).contains(CarFeature.AIRBAGS_FRONT, CarFeature.AIRBAGS_SIDE, CarFeature.AIRBAGS_REAR,
                CarFeature.ABS, CarFeature.ESP, CarFeature.TIRE_PRESSURE_MONITORING, CarFeature.PARKING_SENSORS,
                CarFeature.CAMERA, CarFeature.USB_AUX, CarFeature.TRIP_COMPUTER, CarFeature.POWER_MIRRORS,
                CarFeature.POWER_WINDOWS, CarFeature.DISTANCE_CONTROL, CarFeature.CRUISE_CONTROL,
                CarFeature.PANORAMIC_ROOF, CarFeature.CENTRAL_LOCKING, CarFeature.RAIN_SENSOR, CarFeature.POWER_SEATS,
                CarFeature.LIGHT_SENSOR, CarFeature.KEYLESS_START, CarFeature.FOUR_BY_FOUR, CarFeature.DOORS_4_5)
                .doesNotContain(CarFeature.SUNROOF, CarFeature.RIGHT_HAND_DRIVE, CarFeature.SEVEN_SEATS);

        // the car page's sheet: "Yes" = fitted (""), "No" and "Optional" left out, values kept
        assertThat(s.sheet()).extracting(SpecGroup::name).containsExactly("Motor", "Body Structure",
                "Safety Configuration", "Driving Assistance", "Multimedia", "Glass/Mirrors");
        SpecGroup safety = s.sheet().get(2);
        assertThat(safety.items()).contains(new SpecGroup.Item("Rear Curtain Airbags", ""))
                .extracting(SpecGroup.Item::name).doesNotContain("Rear Side Airbags");
        assertThat(s.sheet().get(3).items()).extracting(SpecGroup.Item::name)
                .doesNotContain("Automatic Parking").contains("Level of Driving Assistance");
        assertThat(s.sheet().get(5).items()).extracting(SpecGroup.Item::name).doesNotContain("Rear Wiper");
    }

    @Test
    void readsACombustionCar() throws IOException {
        ParsedPage page = GuaziPageParser.parse(page("guazi-68pfqu2abf.html"), "68pfqu2abf");

        assertThat(page.priceUsd()).isEqualByComparingTo("7603"); // the current (discounted) price
        assertThat(page.batteryHealthPct()).isNull();
        assertThat(page.images()).hasSize(5);
        assertThat(page.inspection()).isEqualTo("guazi inspection: grade A; issues: Exterior 6");

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
}
