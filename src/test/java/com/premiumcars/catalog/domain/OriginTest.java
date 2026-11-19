package com.premiumcars.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OriginTest {

    @Test
    void testOf_AutotraderCa_IsCanada() {
        assertThat(Origin.of(CarSource.AUTOTRADER_CA)).isEqualTo(Origin.CA);
        assertThat(Origin.CA.sources()).containsExactly(CarSource.AUTOTRADER_CA);
    }

    @Test
    void testOf_ExistingSources_KeepTheirOrigin() {
        assertThat(Origin.of(CarSource.AUTOWINI)).isEqualTo(Origin.KR);
        assertThat(Origin.of(CarSource.GUAZI)).isEqualTo(Origin.CN);
        assertThat(Origin.of(CarSource.MANUAL)).isNull();
    }
}
