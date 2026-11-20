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

    @Test
    void testRefineByTitle_PlugInModelNames_ArePlugInHybrids() {
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "BMW 3 Series (G20) 320e M Sport")).isEqualTo(FuelType.PLUGIN_HYBRID);
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "Mercedes Benz E Class W213 E300e Exclusive")).isEqualTo(FuelType.PLUGIN_HYBRID);
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "Mercedes Benz GLC Class X253 300 e 4Matic")).isEqualTo(FuelType.PLUGIN_HYBRID);
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "Jeep Grand Cherokee 2.0 4XE Summit Reserve")).isEqualTo(FuelType.PLUGIN_HYBRID);
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "BMW X5 (G05) xDrive 45e xLine")).isEqualTo(FuelType.PLUGIN_HYBRID);
    }

    @Test
    void testRefineByTitle_MildOrFullHybridOrOtherFuel_Unchanged() {
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "Mercedes Benz E Class W213 E350 4MATIC AMG Line")).isEqualTo(FuelType.HYBRID);
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "Hyundai The All New Tucson Hybrid 1.6 Turbo 2WD")).isEqualTo(FuelType.HYBRID);
        assertThat(FuelType.refineByTitle(FuelType.HYBRID, "BMW 5 Series 520i Basic")).isEqualTo(FuelType.HYBRID);
        assertThat(FuelType.refineByTitle(FuelType.ELECTRIC, "Kia EV6 Standard 2WD")).isEqualTo(FuelType.ELECTRIC);
        assertThat(FuelType.refineByTitle(FuelType.PETROL, "BMW 330e")).isEqualTo(FuelType.PETROL);
    }
}
