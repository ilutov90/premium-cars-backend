package com.premiumcars.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FuelTypeTest {

    @Test
    void testDetect_AutowiniGasolinePlusElectric_IsHybrid() {
        assertThat(FuelType.detect("Gasoline+Electric")).isEqualTo(FuelType.HYBRID);
        assertThat(FuelType.detect("Gasoline + Electric")).isEqualTo(FuelType.HYBRID);
        assertThat(FuelType.detect("Diesel+Electric")).isEqualTo(FuelType.HYBRID);
        assertThat(FuelType.detect("LPG+Electric")).isEqualTo(FuelType.HYBRID);
    }

    @Test
    void testDetect_PureElectric_StaysElectric() {
        assertThat(FuelType.detect("Electric Vehicle (EV)")).isEqualTo(FuelType.ELECTRIC);
        assertThat(FuelType.detect("Electric")).isEqualTo(FuelType.ELECTRIC);
    }

    @Test
    void testDetect_PlugInHybrid_StaysPlugIn() {
        assertThat(FuelType.detect("Plug-in Hybrid")).isEqualTo(FuelType.PLUGIN_HYBRID);
    }

    @Test
    void testDetect_AutowiniOtherFuelTypes_MapToOursOrOther() {
        assertThat(FuelType.detect("Gasoline+LPG")).isEqualTo(FuelType.LPG);
        assertThat(FuelType.detect("LPG")).isEqualTo(FuelType.LPG);
        assertThat(FuelType.detect("CNG")).isEqualTo(FuelType.OTHER);
        assertThat(FuelType.detect("Others")).isEqualTo(FuelType.OTHER);
        assertThat(FuelType.detect("Gasoline")).isEqualTo(FuelType.PETROL);
        assertThat(FuelType.detect("Diesel")).isEqualTo(FuelType.DIESEL);
    }
}
