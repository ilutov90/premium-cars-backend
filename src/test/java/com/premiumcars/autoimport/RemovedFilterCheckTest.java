package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.autoimport.RemovedFilterCheck.CarFacts;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.FuelType;

class RemovedFilterCheckTest {

    private static final SourceFilters NONE = new SourceFilters(false, false, List.of());
    private static final List<String> KIA_MENU = List.of("K5", "Carnival", "Sportage");

    private static final Brand KIA_ALL = new Brand("C0810", "Kia", true, 2018, List.of());
    private static final Brand KIA_CHOSEN = new Brand("C0810", "Kia", true, 2018,
            List.of(new CatalogModel("S1", "Carnival", List.of("Diesel")), new CatalogModel("S2", "K5")));

    private static CarFacts kia(String menuModel, String model, Integer year, FuelType fuel) {
        return new CarFacts("C0810", "Kia", menuModel, model, year, fuel, null);
    }

    private static boolean autowini(List<Brand> brands, CarFacts car) {
        return RemovedFilterCheck.outOfFilters(CarSource.AUTOWINI, brands, NONE, KIA_MENU, car);
    }

    @Test
    void testOutOfFilters_InsideAllFilters_Counted() {
        assertThat(autowini(List.of(KIA_CHOSEN), kia("Carnival", "Carnival", 2020, FuelType.PETROL))).isFalse();
        assertThat(autowini(List.of(KIA_ALL), kia(null, "Sorento", 2018, FuelType.DIESEL))).isFalse();
    }

    @Test
    void testOutOfFilters_NoBrandRow_Out() {
        assertThat(autowini(List.of(), kia("K5", "K5", 2020, FuelType.PETROL))).isTrue();
    }

    @Test
    void testOutOfFilters_BrandDisabled_Out() {
        Brand off = new Brand("C0810", "Kia", false, 2018, List.of());

        assertThat(autowini(List.of(off), kia("K5", "K5", 2020, FuelType.PETROL))).isTrue();
    }

    @Test
    void testOutOfFilters_NoCodeMakeNameMatches_FoundByName() {
        CarFacts byName = new CarFacts(null, "kia", "K5", "K5", 2020, FuelType.PETROL, null);
        CarFacts otherMake = new CarFacts(null, "Hyundai", "Sonata", "Sonata", 2020, FuelType.PETROL, null);

        assertThat(autowini(List.of(KIA_ALL), byName)).isFalse();
        assertThat(autowini(List.of(KIA_ALL), otherMake)).isTrue();
    }

    @Test
    void testOutOfFilters_MakeUnknown_Counted() {
        CarFacts unknown = new CarFacts(null, null, null, "K5", 2010, FuelType.DIESEL, null);

        assertThat(autowini(List.of(KIA_CHOSEN), unknown)).isFalse();
    }

    @Test
    void testOutOfFilters_YearBelowYearFrom_Out() {
        assertThat(autowini(List.of(KIA_ALL), kia(null, "K5", 2017, FuelType.PETROL))).isTrue();
        assertThat(autowini(List.of(KIA_ALL), kia(null, "K5", null, FuelType.PETROL))).isFalse();
    }

    @Test
    void testOutOfFilters_GuaziYearOneBelow_Counted() {
        Brand byd = new Brand("1", "BYD", true, 2021, List.of());
        CarFacts oneBelow = new CarFacts("1", "BYD", null, "Han", 2020, FuelType.ELECTRIC, null);
        CarFacts twoBelow = new CarFacts("1", "BYD", null, "Han", 2019, FuelType.ELECTRIC, null);

        assertThat(RemovedFilterCheck.outOfFilters(CarSource.GUAZI, List.of(byd), NONE, List.of("Han"), oneBelow))
                .isFalse();
        assertThat(RemovedFilterCheck.outOfFilters(CarSource.GUAZI, List.of(byd), NONE, List.of("Han"), twoBelow))
                .isTrue();
    }

    @Test
    void testOutOfFilters_MenuModelNotChosen_Out() {
        assertThat(autowini(List.of(KIA_CHOSEN), kia("Sportage", "Sportage", 2020, FuelType.PETROL))).isTrue();
    }

    @Test
    void testOutOfFilters_CarModelInMenuNotChosen_Out() {
        assertThat(autowini(List.of(KIA_CHOSEN), kia(null, "sportage", 2020, FuelType.PETROL))).isTrue();
    }

    @Test
    void testOutOfFilters_CarModelNotInMenu_Counted() {
        assertThat(autowini(List.of(KIA_CHOSEN), kia(null, "All New Sportage NQ5", 2020, FuelType.PETROL)))
                .isFalse();
    }

    @Test
    void testOutOfFilters_ChosenModelFuelLeftOut_Out() {
        assertThat(autowini(List.of(KIA_CHOSEN), kia("Carnival", "Carnival", 2020, FuelType.DIESEL))).isTrue();
        assertThat(autowini(List.of(KIA_CHOSEN), kia("K5", "K5", 2020, FuelType.DIESEL))).isFalse();
    }

    @Test
    void testOutOfFilters_MakeFuelLeftOut_Out() {
        Brand noEv = new Brand("C0810", "Kia", true, 2018, List.of(), List.of("Electric Vehicle (EV)"));

        assertThat(autowini(List.of(noEv), kia(null, "EV6", 2022, FuelType.ELECTRIC))).isTrue();
        assertThat(autowini(List.of(noEv), kia(null, "K5", 2022, FuelType.PETROL))).isFalse();
    }

    @Test
    void testOutOfFilters_AmbiguousFuelLeftOut_Counted() {
        Brand noHybrid = new Brand("C0810", "Kia", true, 2018, List.of(), List.of("Gasoline Hybrid", "LPG"));

        assertThat(autowini(List.of(noHybrid), kia(null, "K5", 2022, FuelType.HYBRID))).isFalse();
        assertThat(autowini(List.of(noHybrid), kia(null, "K5", 2022, FuelType.LPG))).isFalse();
    }

    @Test
    void testOutOfFilters_GuaziFuelLeftOut_Counted() {
        Brand byd = new Brand("1", "BYD", true, 2018, List.of(), List.of("Electric Vehicle (EV)"));
        CarFacts han = new CarFacts("1", "BYD", null, "Han", 2022, FuelType.ELECTRIC, null);

        assertThat(RemovedFilterCheck.outOfFilters(CarSource.GUAZI, List.of(byd), NONE, List.of("Han"), han))
                .isFalse();
    }

    @Test
    void testOutOfFilters_GuaziGradeNotChosen_Out() {
        Brand byd = new Brand("1", "BYD", true, 2018, List.of());
        SourceFilters ab = new SourceFilters(false, false, List.of("A", "B"));
        CarFacts gradeC = new CarFacts("1", "BYD", null, "Han", 2022, FuelType.ELECTRIC, "C");
        CarFacts gradeA = new CarFacts("1", "BYD", null, "Han", 2022, FuelType.ELECTRIC, "a");
        CarFacts noGrade = new CarFacts("1", "BYD", null, "Han", 2022, FuelType.ELECTRIC, null);

        assertThat(RemovedFilterCheck.outOfFilters(CarSource.GUAZI, List.of(byd), ab, List.of(), gradeC)).isTrue();
        assertThat(RemovedFilterCheck.outOfFilters(CarSource.GUAZI, List.of(byd), ab, List.of(), gradeA)).isFalse();
        assertThat(RemovedFilterCheck.outOfFilters(CarSource.GUAZI, List.of(byd), ab, List.of(), noGrade)).isFalse();
    }

    @Test
    void testOutOfFilters_AutowiniInspectedFilter_Counted() {
        SourceFilters inspected = new SourceFilters(true, true, List.of());

        assertThat(RemovedFilterCheck.outOfFilters(CarSource.AUTOWINI, List.of(KIA_ALL), inspected, KIA_MENU,
                kia("K5", "K5", 2020, FuelType.PETROL))).isFalse();
    }

    @Test
    void testOutOfFilters_NoFacts_Counted() {
        assertThat(autowini(List.of(KIA_ALL), null)).isFalse();
    }
}
