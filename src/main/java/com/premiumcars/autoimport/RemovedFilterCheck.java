package com.premiumcars.autoimport;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.marketplace.ModelNames;

/**
 * Whether a car the automatic import is about to delete as gone is outside the import's current filters, so its
 * listing was not looked for and may well still be at the source (a make, model, year, fuel type or grade taken out
 * of the filters): such a car is noted {@code out_of_filters} and not counted as sold. Only what can be decided
 * reliably counts as out; anything unknown leaves the car counted. Pure.
 * <ul>
 *   <li>its make has no enabled {@code auto_import_brand} row (by the listing row's make code, else the make's
 *       name);</li>
 *   <li>its year is below the make's "year from" (guazi searches by the year of manufacture, the car keeps the model
 *       year, which may be a year behind: there only a car two years or more below counts as out);</li>
 *   <li>the make has chosen models and the car's menu model (the listing row's, else the car's model when it is one
 *       of the make's menu models) is none of them;</li>
 *   <li>autowini: its fuel type is one the chosen model (or, without chosen models, the make) leaves out; only fuel
 *       types autowini names one way (petrol, diesel, electric, plug-in hybrid, range extender: "Gasoline Hybrid"
 *       and "LPG Hybrid" are both our HYBRID);</li>
 *   <li>autotrader.ca: likewise its fuel type left out, for the fuel types it names one way (gasoline, diesel,
 *       electric, gas/electric hybrid, propane; a plug-in hybrid may be either "Electric" or the hybrid);</li>
 *   <li>guazi: the source's grades are chosen and the car's inspection grade is none of them.</li>
 * </ul>
 * autowini's "Inspected" and "Actual mileage" filters, and autotrader.ca's location, seller type, damage, price and
 * condition filters, cannot be told from the car: such cars stay counted.
 */
public final class RemovedFilterCheck {

    /** What is known of the car about to be deleted; any part may be null. */
    public record CarFacts(String brandCode, String make, String menuModel, String model, Integer year,
                           FuelType fuel, String grade) {
    }

    /** Fuel types autowini's menu has one name for, so a left-out name maps back to the car's own fuel type. */
    private static final Set<FuelType> FUELS_DECIDED = EnumSet.of(FuelType.PETROL, FuelType.DIESEL,
            FuelType.ELECTRIC, FuelType.PLUGIN_HYBRID, FuelType.RANGE_EXTENDER);

    /** The same for autotrader.ca's fuel types (Gasoline, Diesel, Electric, Gas/Electric Hybrid, Propane (LPG)). */
    private static final Set<FuelType> AUTOTRADER_FUELS_DECIDED = EnumSet.of(FuelType.PETROL, FuelType.DIESEL,
            FuelType.ELECTRIC, FuelType.HYBRID, FuelType.LPG);

    private RemovedFilterCheck() {
    }

    /**
     * @param brands   all the source's makes to import (enabled or not)
     * @param filters  the source's own filters (null = unknown)
     * @param makeMenu the make's menu models as the source names them (its list plus the chosen ones)
     */
    public static boolean outOfFilters(CarSource source, List<Brand> brands, SourceFilters filters,
                                       List<String> makeMenu, CarFacts car) {
        if (car == null) {
            return false;
        }
        Brand brand;
        if (present(car.brandCode())) {
            brand = brands.stream().filter(b -> car.brandCode().equals(b.code())).findFirst().orElse(null);
        } else if (present(car.make())) {
            brand = brands.stream().filter(b -> b.name() != null && b.name().trim().equalsIgnoreCase(car.make().trim()))
                    .findFirst().orElse(null);
        } else {
            return false; // the make is not known
        }
        if (brand == null || !brand.enabled()) {
            return true;
        }
        if (car.year() != null && car.year() < brand.yearFrom() - (source == CarSource.GUAZI ? 1 : 0)) {
            return true;
        }
        CatalogModel chosen = null;
        if (!brand.models().isEmpty()) {
            String model = menuModel(car, makeMenu);
            if (model != null) {
                String key = ModelNames.key(model);
                chosen = brand.models().stream().filter(m -> ModelNames.key(m.name()).equals(key)).findFirst()
                        .orElse(null);
                if (chosen == null) {
                    return true;
                }
            }
        }
        Set<FuelType> decided = source == CarSource.AUTOWINI ? FUELS_DECIDED
                : source == CarSource.AUTOTRADER_CA ? AUTOTRADER_FUELS_DECIDED : Set.of();
        if (car.fuel() != null && decided.contains(car.fuel())) {
            List<String> leftOut = brand.models().isEmpty() ? brand.excludedFuels()
                    : chosen != null ? chosen.excludedFuels() : List.of();
            if (leftOut.stream().anyMatch(f -> FuelType.detect(f) == car.fuel())) {
                return true;
            }
        }
        if (source == CarSource.GUAZI && filters != null && !filters.grades().isEmpty() && present(car.grade())) {
            String grade = car.grade().trim().toUpperCase(Locale.ROOT);
            return filters.grades().stream().noneMatch(g -> g != null && g.trim().toUpperCase(Locale.ROOT).equals(grade));
        }
        return false;
    }

    /** The listing row's menu model, else the car's model when the make's menu has it; null = not known. */
    private static String menuModel(CarFacts car, List<String> makeMenu) {
        if (present(car.menuModel())) {
            return car.menuModel().trim();
        }
        if (!present(car.model()) || makeMenu == null) {
            return null;
        }
        String key = ModelNames.key(car.model());
        return makeMenu.stream().filter(m -> ModelNames.key(m).equals(key)).findFirst().orElse(null);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
