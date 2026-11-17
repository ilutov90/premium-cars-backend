package com.premiumcars.seo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeoSlugsTest {

    @Test
    void testOf_HyphenatedMake_KeepsHyphen() {
        assertThat(SeoSlugs.of("Mercedes-Benz")).isEqualTo("mercedes-benz");
    }

    @Test
    void testOf_ParenthesesAndSpaces_BecomeSingleHyphens() {
        assertThat(SeoSlugs.of("KGM (Ssangyong)")).isEqualTo("kgm-ssangyong");
    }

    @Test
    void testOf_ModelWithSpace_JoinsWithHyphen() {
        assertThat(SeoSlugs.of("Model Y")).isEqualTo("model-y");
    }

    @Test
    void testOf_ModelWithDigitsAndDash_KeepsParts() {
        assertThat(SeoSlugs.of("Seal 06 DM-i")).isEqualTo("seal-06-dm-i");
    }

    @Test
    void testOf_SpellingsOfOneModel_GiveTheSameSlug() {
        assertThat(SeoSlugs.of("Alpha One")).isEqualTo(SeoSlugs.of("ALPHA-one")).isEqualTo(SeoSlugs.of(" alpha  one "));
    }

    @Test
    void testOf_Accents_AreStripped() {
        assertThat(SeoSlugs.of("Škoda Čaj")).isEqualTo("skoda-caj");
    }

    @Test
    void testOf_RunsOfSymbols_AreOneHyphenAndTrimmed() {
        assertThat(SeoSlugs.of("--A!!@@B--")).isEqualTo("a-b");
    }

    @Test
    void testOf_OnlySymbolsOrNonLatin_GiveEmptySlug() {
        assertThat(SeoSlugs.of("+++")).isEmpty();
        assertThat(SeoSlugs.of("Москвич")).isEmpty();
        assertThat(SeoSlugs.of("   ")).isEmpty();
        assertThat(SeoSlugs.of(null)).isEmpty();
    }
}
