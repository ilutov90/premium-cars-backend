package com.premiumcars.importing.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.importing.extract.EvSpecsExtractor.EvSpecs;

/** Expected values were produced by extract_ev_specs() of autowini_poster_parallel.py on the same input. */
class EvSpecsExtractorTest {

    @Test
    void tabTextWithDistractors() {
        EvSpecs ev = EvSpecsExtractor.extract(List.of(List.of(
                "Electric Motor", "Total motor power (kW)", "380", "Total motor horsepower (Ps)", "517",
                "Front motor max power (kW)", "150", "Battery && Charging", "Battery capacity (kWh)", "85.4",
                "Battery warranty", "8 years / 150,000 km", "CLTC pure electric range (km)", "605",
                "Fast charging time (hours)", "0.5")));

        assertThat(ev).isEqualTo(new EvSpecs(517, 380, new java.math.BigDecimal("85.4"), 605, "CLTC"));
    }

    @Test
    void inlineLabelsAndFrontPlusRearMotors() {
        EvSpecs ev = EvSpecsExtractor.extract(List.of(List.of(
                "Front motor power (kW): 160", "Rear motor power (kW): 230", "Battery energy (kWh): 100",
                "NEDC electric range (km): 500")));

        assertThat(ev.motorPowerKw()).isEqualTo(390);
        assertThat(ev.powerHp()).isEqualTo(530); // 390 kW x 1.36
        assertThat(ev.batteryKwh()).hasToString("100");
        assertThat(ev.electricRangeKm()).isEqualTo(500);
        assertThat(ev.rangeStandard()).isEqualTo("NEDC");
    }

    @Test
    void missingValuesAndThousands() {
        EvSpecs ev = EvSpecsExtractor.extract(List.of(List.of(
                "Electric motor (Ps)", "218", "Battery type", "LFP", "Battery capacity(kWh)", "--",
                "WLTC range (km)", "1,200")));

        assertThat(ev).isEqualTo(new EvSpecs(218, null, null, 1200, "WLTC"));
    }

    @Test
    void escapedNextJsJson() {
        String payload = "{\\\"groupName\\\":\\\"Battery && Charging\\\",\\\"list\\\":[{\\\"name\\\":\\\"Battery capacity (kWh)\\\","
                + "\\\"value\\\":\\\"82.5\\\"},{\\\"name\\\":\\\"CLTC pure electric range (km)\\\",\\\"value\\\":650},"
                + "{\\\"name\\\":\\\"Total motor power (kW)\\\",\\\"value\\\":390}]}";

        EvSpecs ev = EvSpecsExtractor.extract(List.of(EvSpecsExtractor.jsonTokens(payload)));

        assertThat(ev).isEqualTo(new EvSpecs(530, 390, new java.math.BigDecimal("82.5"), 650, "CLTC"));
    }

    @Test
    void titles() {
        assertThat(TitleParser.split("Li Auto L9 2023 Max")).isEqualTo(new TitleParser.Parts("Li Auto", "L9", "Max"));
        assertThat(TitleParser.split("BYD Tang New Energy 2019 DM 2.0T"))
                .isEqualTo(new TitleParser.Parts("BYD", "Tang New Energy", "DM 2.0T"));
        assertThat(TitleParser.split("Great Wall Poer 2021 2.0T Commercial").make()).isEqualTo("Great Wall");
    }
}
