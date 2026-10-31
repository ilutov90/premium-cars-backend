package com.premiumcars.carsbg;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.carsbg.CarsBgMapper.Warning.Code;
import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;

/** The cars.bg form filled in from a car; no database, no network. */
class CarsBgMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    private static final CarsBgCatalog CATALOG = new CarsBgCatalog(List.of(
            new CarsBgCatalog.Brand("151", "BYD", List.of(new CarsBgCatalog.Model("9001", "Atto2"),
                    new CarsBgCatalog.Model("9002", "Han"))),
            new CarsBgCatalog.Brand("33", "Hyundai", List.of(new CarsBgCatalog.Model("500", "Santa Fe")))),
            NOW);

    private static Car car(CarSpecs specs, String total) {
        return new Car(7, "car-7", CarStatus.PUBLISHED, CarSource.GUAZI, null, null, null, specs, null,
                total == null ? null : new BigDecimal(total), NOW, NOW, null);
    }

    @Test
    void fillsTheFormInCarsBgsIds() {
        CarSpecs specs = CarSpecs.builder().make("Hyundai").model("SantaFe").trim("2.5T 2WD Prestige").year(2024)
                .firstRegistration("2023-09").mileageKm(116_710).fuelType(FuelType.PETROL)
                .transmission(Transmission.AUTOMATIC).engineCc(2497).powerHp(281).euroStandard(6).bodyType("SUV")
                .doors(5).color("Silver Metallic")
                .features(List.of(CarFeature.HEATED_SEATS, CarFeature.ESP, CarFeature.DYNAMIC_STABILITY,
                        CarFeature.AIRBAGS_FRONT, CarFeature.AIRBAGS_SIDE, CarFeature.LEASING,
                        CarFeature.CAMERA))
                .build();
        CarsBgMapper.Result r = CarsBgMapper.map(car(specs, "26109.60"), CATALOG, 2026);
        CarsBgDraft d = r.draft();

        assertThat(d.brandId()).isEqualTo("33");
        assertThat(d.modelId()).isEqualTo("500");
        assertThat(d.modelName()).isEqualTo("Santa Fe");
        assertThat(d.engine()).isEqualTo("2.5T 2WD"); // the trim in whole words, 15 characters at most
        assertThat(d.fullName()).isEqualTo("Hyundai SantaFe 2.5T 2WD Prestige");
        assertThat(d.categoryId()).isEqualTo(7);
        assertThat(d.fuelId()).isEqualTo(1);
        assertThat(d.gearId()).isEqualTo(2);
        assertThat(d.cubature()).isEqualTo(2497);
        assertThat(d.productionYear()).isEqualTo(2023);
        assertThat(d.monthId()).isEqualTo(9);
        assertThat(d.run()).isEqualTo(116_710);
        assertThat(d.doorId()).isEqualTo(2);
        assertThat(d.colorId()).isEqualTo(13);
        assertThat(d.metallic()).isTrue();
        assertThat(d.euroId()).isEqualTo(6);
        assertThat(d.price()).isEqualByComparingTo("26110");
        assertThat(d.currencyId()).isEqualTo(3);
        assertThat(d.conditionId()).isEqualTo(1);
        assertThat(d.inBulgaria()).isFalse();
        assertThat(d.leasing()).isTrue();
        assertThat(d.extras()).containsExactly(7, 13, 14); // sorted, no duplicates; the camera has no checkbox
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void exportNamesNextYearsCarsAndMissingThings() {
        CarSpecs specs = CarSpecs.builder().make("BYD").model("Yuan UP").year(2027).fuelType(FuelType.ELECTRIC)
                .engineCc(0).bodyType("MPV").color("Aurora").build();
        CarsBgMapper.Result r = CarsBgMapper.map(car(specs, null), CATALOG, 2026);
        assertThat(r.draft().modelId()).isEqualTo("9001");
        assertThat(r.draft().engine()).isEqualTo("Yuan UP"); // our name, cars.bg's is "Atto2"
        assertThat(r.draft().productionYear()).isEqualTo(2026);
        assertThat(r.draft().fuelId()).isEqualTo(7);
        assertThat(r.draft().cubature()).isNull();
        assertThat(r.draft().categoryId()).isEqualTo(9);
        assertThat(r.warnings()).extracting(CarsBgMapper.Warning::code).containsExactlyInAnyOrder(
                Code.MODEL_SUGGESTED, Code.YEAR_ADJUSTED, Code.CATEGORY_MPV, Code.COLOR_UNKNOWN, Code.DOORS_UNKNOWN,
                Code.PRODUCTION_MONTH_UNKNOWN, Code.NO_PRICE);

        CarsBgMapper.Result avatr = CarsBgMapper.map(car(CarSpecs.builder().make("Avatr").model("11").build(), "1"),
                CATALOG, 2026);
        assertThat(avatr.draft().brandId()).isNull();
        assertThat(avatr.warnings()).extracting(CarsBgMapper.Warning::code).contains(Code.BRAND_NOT_FOUND);

        CarsBgMapper.Result none = CarsBgMapper.map(car(CarSpecs.builder().make("BYD").model("Han").build(), "1"),
                CarsBgCatalog.EMPTY, 2026);
        assertThat(none.warnings()).extracting(CarsBgMapper.Warning::code).contains(Code.CATALOG_MISSING);
    }

    @Test
    void fuelsColoursAndWords() {
        assertThat(CarsBgMapper.fuel(FuelType.PLUGIN_HYBRID, List.of())).isEqualTo(6);
        assertThat(CarsBgMapper.fuel(FuelType.RANGE_EXTENDER, List.of())).isEqualTo(6);
        assertThat(CarsBgMapper.fuel(FuelType.PETROL, List.of(CarFeature.LPG))).isEqualTo(3);
        assertThat(CarsBgMapper.color("Black Pearl")).isEqualTo(10);
        assertThat(CarsBgMapper.color("Pearl White")).isEqualTo(1);
        assertThat(CarsBgMapper.color("Dark Grey")).isEqualTo(23);
        assertThat(CarsBgMapper.color("Navy Blue")).isEqualTo(22);
        assertThat(CarsBgMapper.words("Galaxy Starship 7 EM-i", 15)).isEqualTo("Galaxy Starship");
        assertThat(CarsBgMapper.words("Supercalifragilistic", 15)).hasSize(15);
    }
}
