package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.RemovedCarNames.Make;
import com.premiumcars.autoimport.RemovedCarNames.Names;

class RemovedCarNamesTest {

    private static final int THIS_YEAR = 2026;

    private static final List<Make> AUTOWINI = List.of(
            new Make("C0110", "Hyundai", List.of("Grandeur", "Santa Fe", "Sonata", "Tucson")),
            new Make("C0810", "Kia", List.of("K3", "K5", "Carnival", "Sportage")),
            new Make("C1600", "Land Rover", List.of("Range Rover", "Range Rover Sport", "Discovery")),
            new Make("C1650", "Rover", List.of("75")),
            new Make("C1900", "Mercedes-Benz", List.of("C-Class", "E-Class")));

    private static final List<Make> GUAZI = List.of(
            new Make("1", "BYD", List.of("Seal", "Seal 06 DM-i", "Song PLUS New Energy", "Song MAX", "Han")),
            new Make("2", "Geely Auto", List.of("Xingyue L", "Boyue")),
            new Make("3", "Geely Galaxy", List.of("E5", "L7")),
            new Make("4", "Tesla", List.of("Model 3", "Model Y")));

    @Test
    void testFromText_LandRoverTitle_LongestMakeAndModelWin() {
        Names n = RemovedCarNames.fromText("Land Rover Range Rover Sport 2019 3.0 SDV6",
                "land-rover-range-rover-sport-2019-41", AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Land Rover", "Range Rover Sport", 2019));
    }

    @Test
    void testFromText_GuaziModelWithDashes_FindsMenuModel() {
        Names n = RemovedCarNames.fromText("BYD Seal 06 DM-i 2024 120KM Flagship", "byd-seal-06-dm-i-2024-512",
                GUAZI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("BYD", "Seal 06 DM-i", 2024));
    }

    @Test
    void testFromText_MenuModelWithPowertrainWords_FindsMenuModel() {
        Names n = RemovedCarNames.fromText("BYD Song PLUS DM-i 2023 110KM Flagship PLUS", "byd-song-plus-2023-88",
                GUAZI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("BYD", "Song PLUS New Energy", 2023));
    }

    @Test
    void testFromText_ModelWithSpaces_FindsThatModel() {
        Names n = RemovedCarNames.fromText("Tesla Model Y 2025 Long-Range All-Wheel-Drive Version",
                "tesla-model-y-2025-301", GUAZI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Tesla", "Model Y", 2025));
    }

    @Test
    void testFromText_MakeNotInTheLists_ReturnsNoMake() {
        Names n = RemovedCarNames.fromText("Toyota Camry 2020 2.5 Hybrid", "toyota-camry-2020-17", AUTOWINI,
                THIS_YEAR);

        assertThat(n).isEqualTo(new Names(null, null, 2020));
    }

    @Test
    void testFromText_YearInSlug_PrefersSlugOverIdAndTitle() {
        Names n = RemovedCarNames.fromText("Hyundai Grandeur IG 2.4 Modern 2019", "hyundai-grandeur-2021-2020",
                AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Hyundai", "Grandeur", 2021));
    }

    @Test
    void testFromText_AutowiniTitleStartingWithYear_FindsMakeModelAndYear() {
        Names n = RemovedCarNames.fromText("2020 Kia All NEW K3 1.6 A/T", "kia-k3-15", AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Kia", "K3", 2020));
    }

    @Test
    void testFromText_MakeWithDashInMenu_FindsMakeAndModel() {
        Names n = RemovedCarNames.fromText("Mercedes Benz E Class W213 E300", "mercedes-benz-e-class-2018-90",
                AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Mercedes-Benz", "E-Class", 2018));
    }

    @Test
    void testFromText_ModelWrittenTogether_FindsMenuModel() {
        Names n = RemovedCarNames.fromText("Hyundai SantaFe (MX5) Gasoline 2.5T 2WD Prestige", "hyundai-santafe-7",
                AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Hyundai", "Santa Fe", null));
    }

    @Test
    void testFromText_SubBrandsOfOneMake_PicksTheOneWithTheModel() {
        Names auto = RemovedCarNames.fromText("Geely Xingyue L 2023 2.0T Flagship", "geely-xingyue-l-2023-77",
                GUAZI, THIS_YEAR);
        Names galaxy = RemovedCarNames.fromText("Geely Galaxy E5 2025 550km", "geely-galaxy-e5-2025-78", GUAZI,
                THIS_YEAR);

        assertThat(auto).isEqualTo(new Names("Geely Auto", "Xingyue L", 2023));
        assertThat(galaxy).isEqualTo(new Names("Geely Galaxy", "E5", 2025));
    }

    @Test
    void testFromText_ModelNotInMenu_ReturnsMakeWithoutModel() {
        Names n = RemovedCarNames.fromText("Kia EV9 2024 GT-Line", "kia-ev9-2024-5", AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Kia", null, 2024));
    }

    @Test
    void testFromText_YearOutOfRange_ReturnsNoYear() {
        assertThat(RemovedCarNames.fromText("Kia K5 1985", null, AUTOWINI, THIS_YEAR).year()).isNull();
        assertThat(RemovedCarNames.fromText("Kia K5 2028", null, AUTOWINI, THIS_YEAR).year()).isNull();
        assertThat(RemovedCarNames.fromText("Kia K5 2027", null, AUTOWINI, THIS_YEAR).year()).isEqualTo(2027);
    }

    @Test
    void testFromText_NothingGiven_ReturnsNothing() {
        assertThat(RemovedCarNames.fromText(null, null, AUTOWINI, THIS_YEAR)).isEqualTo(Names.NONE);
    }

    @Test
    void testFromCar_ListingRow_TakesItsMakeAndMenuModel() {
        Names n = RemovedCarNames.fromCar("C0810", "K5", "Kia", "The New K5", 2022, "Kia The New K5 2.0",
                "kia-the-new-k5-2022-3", AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Kia", "K5", 2022));
    }

    @Test
    void testFromCar_CanonicalMakeWithoutListingRow_FindsSubBrandAndMenuModel() {
        Names n = RemovedCarNames.fromCar(null, null, "Geely", "Galaxy E5", 2025, "Geely Galaxy E5 2025",
                "geely-galaxy-e5-2025-4", GUAZI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Geely Galaxy", "E5", 2025));
    }

    @Test
    void testFromCar_ModelNotInMenu_KeepsTheCarsModel() {
        Names n = RemovedCarNames.fromCar(null, null, "kia", "EV9", 2024, "Kia EV9 2024", "kia-ev9-2024-5",
                AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Kia", "EV9", 2024));
    }

    @Test
    void testFromCar_UnknownCodeAndMake_NamesItFromTheTitle() {
        Names n = RemovedCarNames.fromCar("X999", null, "Car", null, null, "Hyundai Tucson 2023 1.6T",
                "car-2023-6", AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names("Hyundai", "Tucson", 2023));
    }

    @Test
    void testFromCar_MakeNotInTheLists_KeepsModelWithoutMake() {
        Names n = RemovedCarNames.fromCar(null, null, "Toyota", "Camry", 2020, "Toyota Camry 2020",
                "toyota-camry-2020-8", AUTOWINI, THIS_YEAR);

        assertThat(n).isEqualTo(new Names(null, "Camry", 2020));
    }

    @Test
    void testMakes_CatalogAndChosenBrands_MergesModelsByCode() {
        List<Make> makes = RemovedCarNames.makes(
                List.of(new CatalogBrand("C0810", "Kia", List.of(new CatalogModel("C1150", "Carnival"))),
                        new CatalogBrand("C0110", "Hyundai", null)),
                List.of(new Brand("C0810", "Kia", true, 2018, List.of(new CatalogModel("C1200", "K5")), List.of()),
                        new Brand("C1600", "Land Rover", true, 2018, List.of(), List.of())));

        assertThat(makes).containsExactly(new Make("C0810", "Kia", List.of("Carnival", "K5")),
                new Make("C0110", "Hyundai", List.of()), new Make("C1600", "Land Rover", List.of()));
    }
}
