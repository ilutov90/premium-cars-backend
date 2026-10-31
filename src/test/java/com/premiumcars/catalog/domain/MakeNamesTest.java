package com.premiumcars.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MakeNamesTest {

    @Test
    void subBrandsBecomeTheMake() {
        assertThat(MakeNames.canonical("Geely Auto", "Monjaro")).isEqualTo(new MakeNames.Name("Geely", "Monjaro"));
        assertThat(MakeNames.canonical("Geely Galaxy", "E5")).isEqualTo(new MakeNames.Name("Geely", "Galaxy E5"));
        assertThat(MakeNames.canonical("Geely Galaxy", "Galaxy L7"))
                .isEqualTo(new MakeNames.Name("Geely", "Galaxy L7"));
        assertThat(MakeNames.canonical("Dongfeng Aeolus", "Haoji")).isEqualTo(new MakeNames.Name("Dongfeng", "Haoji"));
        assertThat(MakeNames.canonical("Xiaomi Auto", "SU7")).isEqualTo(new MakeNames.Name("Xiaomi", "SU7"));
        assertThat(MakeNames.canonical("Kia", "K5")).isEqualTo(new MakeNames.Name("Kia", "K5"));
        assertThat(MakeNames.canonical(null, "X")).isEqualTo(new MakeNames.Name(null, "X"));
    }
}
