package com.premiumcars.mobilebg;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.mobilebg.MobileBgMapper.Warning.Code;

/** The mobile.bg form filled in from a car; no database, no network. */
class MobileBgMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private static final MobileBgCatalog CATALOG = new MobileBgCatalog(
            List.of(new MobileBgCatalog.Brand("Hyundai", List.of("Santa Fe", "Sonata", "Tucson")),
                    new MobileBgCatalog.Brand("Kia", List.of("Sportage", "Sorento")),
                    new MobileBgCatalog.Brand("Mercedes-Benz", List.of("E 300", "EQE", "GLE 350")),
                    new MobileBgCatalog.Brand("BYD", List.of("Han", "Seal")),
                    new MobileBgCatalog.Brand("VW", List.of("Golf", "ID.4"))),
            Map.of("f17", List.of(new MobileBgCatalog.Option("Бял", "Бял"), new MobileBgCatalog.Option("Сив", "Сив"),
                    new MobileBgCatalog.Option("Сребърен", "Сребърен"))),
            List.of(), NOW);

    private static Car car(CarSpecs specs, String total) {
        return new Car(7, "car-7", CarStatus.PUBLISHED, CarSource.AUTOWINI, null, null, null, specs, null,
                total == null ? null : new BigDecimal(total), NOW, NOW, null);
    }

    @Test
    void fillsTheFormInMobileBgsValues() {
        CarSpecs specs = CarSpecs.builder().title("Hyundai SantaFe").make("Hyundai").model("SantaFe")
                .trim("(MX5) Gasoline 2.5T 2WD Prestige").year(2024).firstRegistration("2023-09").mileageKm(116_710)
                .fuelType(FuelType.PETROL).transmission(Transmission.AUTOMATIC).engineCc(2497).powerHp(281)
                .euroStandard(6).bodyType("SUV").color("Black Pearl").features(List.of(CarFeature.HEATED_SEATS))
                .build();
        MobileBgMapper.Result r = MobileBgMapper.map(car(specs, "26109.60"), CATALOG);
        MobileBgDraft d = r.draft();

        assertThat(d.brand()).isEqualTo("Hyundai");
        assertThat(d.model()).isEqualTo("Santa Fe"); // "SantaFe" = "Santa Fe"
        assertThat(d.modification()).isEqualTo("(MX5) Gasoline 2.5T 2WD Prestige");
        assertThat(d.engineType()).isEqualTo("Бензинов");
        assertThat(d.condition()).isEqualTo("0");
        assertThat(d.gearbox()).isEqualTo("Автоматична");
        assertThat(d.category()).isEqualTo("Джип");
        assertThat(d.engineCc()).isEqualTo(2497);
        assertThat(d.price()).isEqualByComparingTo("26110");
        assertThat(d.vat()).isEqualTo("2");
        assertThat(d.currency()).isEqualTo("EUR");
        assertThat(d.month()).isEqualTo("септември");
        assertThat(d.year()).isEqualTo(2023); // the first registration, not the model year
        assertThat(d.region()).isEqualTo("София");
        assertThat(d.city()).isEqualTo("гр. София");
        assertThat(d.rangeKm()).isNull();
        assertThat(d.extras()).containsExactly(CarFeature.HEATED_SEATS);
        // "Black Pearl" is black, which this list of mobile.bg's colours does not have: left to the admin
        assertThat(r.warnings()).extracting(MobileBgMapper.Warning::code).containsExactly(Code.COLOR_UNKNOWN);
    }

    @Test
    void brandAliasesAndTheLongestModelThatFits() {
        CarSpecs merc = CarSpecs.builder().make("Mercedes").model("GLE").trim("350 4MATIC").build();
        assertThat(MobileBgMapper.map(car(merc, "50000"), CATALOG).draft())
                .extracting(MobileBgDraft::brand, MobileBgDraft::model).containsExactly("Mercedes-Benz", "GLE 350");

        CarSpecs vw = CarSpecs.builder().make("Volkswagen").model("ID.4 CROZZ").build();
        assertThat(MobileBgMapper.map(car(vw, "30000"), CATALOG).draft().brand()).isEqualTo("VW");
        assertThat(MobileBgMapper.map(car(vw, "30000"), CATALOG).draft().model()).isEqualTo("ID.4");

        CarSpecs kia = CarSpecs.builder().make("KIA").model("Sportage R").build();
        assertThat(MobileBgMapper.map(car(kia, "20000"), CATALOG).draft().model()).isEqualTo("Sportage");
    }

    @Test
    void aGuessedModelIsSentWithAWarning() {
        CarSpecs kia = CarSpecs.builder().make("KIA").model("Sportage R").build();
        MobileBgMapper.Result r = MobileBgMapper.map(car(kia, "20000"), CATALOG);
        assertThat(r.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo(Code.MODEL_SUGGESTED);
            assertThat(w.value()).isEqualTo("Sportage");
        });
    }

    @Test
    void unknownBrandOrModelIsLeftToTheAdmin() {
        CarSpecs zeekr = CarSpecs.builder().make("Zeekr").model("001").build();
        MobileBgMapper.Result r = MobileBgMapper.map(car(zeekr, "40000"), CATALOG);
        assertThat(r.draft().brand()).isNull();
        assertThat(r.draft().model()).isNull();
        assertThat(r.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo(Code.BRAND_NOT_FOUND);
            assertThat(w.value()).isEqualTo("Zeekr");
        });

        CarSpecs byd = CarSpecs.builder().make("BYD").model("Song Plus").build();
        MobileBgMapper.Result r2 = MobileBgMapper.map(car(byd, "40000"), CATALOG);
        assertThat(r2.draft().brand()).isEqualTo("BYD");
        assertThat(r2.draft().model()).isNull();
        assertThat(r2.warnings()).extracting(MobileBgMapper.Warning::code).contains(Code.MODEL_NOT_FOUND);
    }

    @Test
    void withoutMobileBgsListsTheCarsOwnNamesAreUsed() {
        CarSpecs specs = CarSpecs.builder().make("Hyundai").model("SantaFe").color("White").build();
        MobileBgMapper.Result r = MobileBgMapper.map(car(specs, null), MobileBgCatalog.EMPTY);
        assertThat(r.draft().brand()).isEqualTo("Hyundai");
        assertThat(r.draft().model()).isEqualTo("SantaFe");
        assertThat(r.draft().color()).isEqualTo("Бял"); // our guess, matched by text in mobile.bg
        assertThat(r.draft().price()).isNull();
        assertThat(r.warnings()).extracting(MobileBgMapper.Warning::code)
                .contains(Code.CATALOG_MISSING, Code.NO_PRICE, Code.PRODUCTION_MONTH_UNKNOWN, Code.CATEGORY_UNKNOWN);
    }

    @Test
    void electricAndPlugInCars() {
        CarSpecs ev = CarSpecs.builder().make("BYD").model("Han").fuelType(FuelType.ELECTRIC).engineCc(0)
                .batteryKwh(new BigDecimal("76.9")).electricRangeKm(605).rangeStandard("NEDC").bodyType("Sedan")
                .color("Silver").build();
        MobileBgMapper.Result r = MobileBgMapper.map(car(ev, "21077"), CATALOG);
        assertThat(r.draft().engineType()).isEqualTo("Електрически");
        assertThat(r.draft().engineCc()).isNull();
        assertThat(r.draft().rangeKm()).isEqualTo(605);
        assertThat(r.draft().batteryKwh()).isEqualByComparingTo("76.9");
        assertThat(r.draft().category()).isEqualTo("Седан");
        assertThat(r.draft().color()).isEqualTo("Сребърен");
        assertThat(r.warnings()).extracting(MobileBgMapper.Warning::code).contains(Code.RANGE_NOT_WLTP);

        CarSpecs erev = CarSpecs.builder().make("Li Auto").model("L7").fuelType(FuelType.RANGE_EXTENDER)
                .electricRangeKm(210).rangeStandard("WLTP").build();
        MobileBgMapper.Result r2 = MobileBgMapper.map(car(erev, "45000"), CATALOG);
        assertThat(r2.draft().engineType()).isEqualTo("Plug-in хибрид");
        assertThat(r2.warnings()).extracting(MobileBgMapper.Warning::code)
                .contains(Code.RANGE_EXTENDER, Code.BRAND_NOT_FOUND).doesNotContain(Code.RANGE_NOT_WLTP);

        CarSpecs petrol = CarSpecs.builder().make("Kia").model("Sorento").fuelType(FuelType.PETROL)
                .electricRangeKm(50).build();
        assertThat(MobileBgMapper.map(car(petrol, "1"), CATALOG).draft().rangeKm()).isNull();
    }

    @Test
    void categories() {
        assertThat(MobileBgMapper.category("SUV")).isEqualTo("Джип");
        assertThat(MobileBgMapper.category("Station Wagon")).isEqualTo("Комби");
        assertThat(MobileBgMapper.category("MPV")).isEqualTo("Миниван");
        assertThat(MobileBgMapper.category("Hatchback")).isEqualTo("Хечбек");
        assertThat(MobileBgMapper.category("Pickup")).isEqualTo("Пикап");
        assertThat(MobileBgMapper.category("Hardtop Coupe")).isEqualTo("Купе");
        assertThat(MobileBgMapper.category("Convertible")).isEqualTo("Кабрио");
        assertThat(MobileBgMapper.category("Van")).isEqualTo("Ван");
        assertThat(MobileBgMapper.category("Truck")).isNull();
    }
}
