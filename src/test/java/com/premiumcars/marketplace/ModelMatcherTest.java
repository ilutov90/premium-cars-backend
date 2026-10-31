package com.premiumcars.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.premiumcars.marketplace.ModelMatch.Status;
import com.premiumcars.marketplace.SiteCatalog.SiteBrand;
import com.premiumcars.marketplace.SiteCatalog.SiteModel;

/** Our makes + models against a listing site's lists; no database, no network. */
class ModelMatcherTest {

    private static SiteModel m(String id, String name) {
        return new SiteModel(id, name);
    }

    private static final SiteCatalog CARS_BG = new SiteCatalog(Site.CARS_BG, List.of(
            new SiteBrand("151", "BYD", List.of(m("1", "Atto2"), m("2", "Atto3"), m("3", "Seal"), m("4", "Seal U"),
                    m("5", "Sealion 7"), m("6", "Han"), m("7", "Song Plus"))),
            new SiteBrand("161", "Geely", List.of(m("10", "EX5"), m("11", "Monjaro"), m("12", "Coolray"))),
            new SiteBrand("38", "Kia", List.of(m("20", "K 3"), m("21", "Sportage"))),
            new SiteBrand("155", "Lynk and Co", List.of(m("30", "01"))),
            new SiteBrand("106", "Range Rover", List.of(m("40", "Sport"))),
            new SiteBrand("43", "Land Rover", List.of(m("41", "Defender"))),
            new SiteBrand("54", "Mercedes-Benz", List.of(m("50", "GLE"), m("51", "GLE 350"), m("52", "E")))),
            null);

    private static ModelMatch match(String make, String model) {
        return ModelMatcher.match(CARS_BG, make, model, Optional.empty());
    }

    @Test
    void theSameNameIsMatchedByItself() {
        assertThat(match("Kia", "K3").model()).isEqualTo(m("20", "K 3"));
        assertThat(match("Kia", "K3").status()).isEqualTo(Status.AUTO);
        assertThat(match("BYD", "Song PLUS New Energy").model().name()).isEqualTo("Song Plus");
        assertThat(match("BYD", "Han EV").model().name()).isEqualTo("Han");
        assertThat(match("Lynk & Co", "01").brand().name()).isEqualTo("Lynk and Co");
        assertThat(match("Mercedes", "GLE").model().name()).isEqualTo("GLE");
        assertThat(match("Range Rover", "Sport").brand().id()).isEqualTo("106");
    }

    @Test
    void exportNamesAndLongerNamesAreOnlySuggested() {
        ModelMatch yuan = match("BYD", "Yuan UP");
        assertThat(yuan.status()).isEqualTo(Status.SUGGESTED);
        assertThat(yuan.model().name()).isEqualTo("Atto2");
        assertThat(match("BYD", "Yuan PLUS").model().name()).isEqualTo("Atto3");
        assertThat(match("BYD", "Song PLUS").status()).isEqualTo(Status.AUTO);
        assertThat(match("Geely", "Galaxy E5").model().name()).isEqualTo("EX5");
        assertThat(match("Geely", "Xingyue L").model().name()).isEqualTo("Monjaro");
        assertThat(match("BYD", "Sealion 07").suggestions()).extracting(SiteModel::name)
                .startsWith("Sealion 7");
        // "Seal 06" is not the "Seal": offered, never taken alone
        assertThat(match("BYD", "Seal 06 DM-i").status()).isEqualTo(Status.SUGGESTED);
        assertThat(match("Kia", "Sportage R").model().name()).isEqualTo("Sportage");
        assertThat(ModelMatcher.match(CARS_BG, "Mercedes", "GL", "E 350 4MATIC", Optional.empty())
                .suggestions()).extracting(SiteModel::name).startsWith("GLE 350");
    }

    @Test
    void missingMakesAndModels() {
        assertThat(match("Avatr", "11").status()).isEqualTo(Status.BRAND_MISSING);
        assertThat(match("Hongqi", "H9").brand()).isNull();
        ModelMatch tank = match("Geely", "Boyue");
        assertThat(tank.status()).isEqualTo(Status.MODEL_MISSING);
        assertThat(tank.brand().name()).isEqualTo("Geely");
        assertThat(ModelMatcher.match(new SiteCatalog(Site.CARS_BG, List.of(), null), "Kia", "K3", Optional.empty())
                .status()).isEqualTo(Status.NO_CATALOG);
    }

    @Test
    void theAdminsChoiceComesFirst() {
        ModelMapping atto = new ModelMapping(Site.CARS_BG, "BYD", "Yuan UP", "BYD", "Atto2", false, "ivan", null);
        ModelMatch r = ModelMatcher.match(CARS_BG, "BYD", "Yuan UP", Optional.of(atto));
        assertThat(r.status()).isEqualTo(Status.MANUAL);
        assertThat(r.model().id()).isEqualTo("1");
        assertThat(r.usable()).isTrue();

        ModelMapping none = new ModelMapping(Site.CARS_BG, "BYD", "Seal 06", null, null, true, "ivan", null);
        ModelMatch n = ModelMatcher.match(CARS_BG, "BYD", "Seal 06", Optional.of(none));
        assertThat(n.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(n.usable()).isFalse();

        // a chosen model gone from the site's lists: matched again
        ModelMapping gone = new ModelMapping(Site.CARS_BG, "Kia", "K3", "Kia", "K3 Cross", false, "ivan", null);
        assertThat(ModelMatcher.match(CARS_BG, "Kia", "K3", Optional.of(gone)).status()).isEqualTo(Status.AUTO);
    }

    @Test
    void keys() {
        assertThat(ModelNames.key("Santa-Fe ")).isEqualTo("santafe");
        assertThat(ModelNames.modelKey("Song PLUS DM-i (Chinese Domestic Model)")).isEqualTo("songplus");
        assertThat(ModelNames.modelKey("EV6")).isEqualTo("ev6");
        assertThat(ModelNames.modelKey("EV")).isEqualTo("ev");
        assertThat(ModelNames.makeAlias(Site.MOBILE_BG, "Lynk & Co")).isEqualTo("Lynk & Co");
        assertThat(ModelNames.makeAlias(Site.CARS_BG, "Lynk&Co")).isEqualTo("Lynk and Co");
        assertThat(ModelNames.makeAlias(Site.MOBILE_BG, "Range Rover")).isEqualTo("Land Rover");
    }
}
