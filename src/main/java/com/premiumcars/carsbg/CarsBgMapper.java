package com.premiumcars.carsbg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.marketplace.ModelMatch;
import com.premiumcars.marketplace.ModelMatcher;
import com.premiumcars.marketplace.ModelNames;

/**
 * Fills cars.bg's "Публикувай обява" form from a car, in cars.bg's ids. The make and model come from the
 * {@link ModelMatch} (cars.bg's lists + the admins' choices); what cannot be filled is reported as a
 * {@link Warning} and left to the admin. Pure: no database, no network.
 */
public final class CarsBgMapper {

    /** Problems the admin should look at; {@code value} is what the car has. The admin panel words them. */
    public record Warning(Code code, String value) {
        public enum Code {
            /** No cars.bg lists yet: the make and model cannot be chosen. */
            CATALOG_MISSING,
            /** cars.bg has no such make: the car cannot be posted there. */
            BRAND_NOT_FOUND,
            /** cars.bg has the make, not the model: choose it on the mapping page. */
            MODEL_NOT_FOUND,
            /** The model was guessed (value: cars.bg's): check it, or confirm it on the mapping page. */
            MODEL_SUGGESTED,
            /** An admin said cars.bg has no model for it. */
            MODEL_UNAVAILABLE,
            CATEGORY_UNKNOWN,
            /** cars.bg has no minivan: sent as a van. */
            CATEGORY_MPV,
            COLOR_UNKNOWN,
            DOORS_UNKNOWN,
            /** cars.bg's years end with this year (value: the car's year): this year was sent. */
            YEAR_ADJUSTED,
            PRODUCTION_MONTH_UNKNOWN,
            NO_PRICE
        }
    }

    public record Result(CarsBgDraft draft, List<Warning> warnings) {
    }

    /** cars.bg's {@code engine} field takes 15 characters. */
    static final int ENGINE_MAX = 15;

    /** Words in the source's body type -> cars.bg's category id (first match wins). */
    private static final List<Map.Entry<Pattern, Integer>> CATEGORIES = List.of(
            Map.entry(Pattern.compile("suv|crossover|jeep|off.?road|джип"), 7),
            Map.entry(Pattern.compile("pick.?up|пикап"), 8),
            Map.entry(Pattern.compile("wagon|estate|touring|комби"), 4),
            Map.entry(Pattern.compile("mpv|minivan|mini.?van|миниван"), 9),
            Map.entry(Pattern.compile("\\bvan\\b|\\bbus\\b|ван"), 9),
            Map.entry(Pattern.compile("convertible|cabrio|roadster|кабрио"), 6),
            Map.entry(Pattern.compile("coupe|купе"), 5),
            Map.entry(Pattern.compile("hatch|liftback|хечбек"), 2),
            Map.entry(Pattern.compile("sedan|saloon|седан"), 1));
    private static final Pattern MPV = Pattern.compile("mpv|minivan|mini.?van|миниван");

    /** Source colours (English) -> cars.bg's colour id (first match wins). */
    private static final List<Map.Entry<Pattern, Integer>> COLORS = List.of(
            Map.entry(Pattern.compile("black|черен"), 10), // before "pearl": "Black Pearl" is black
            Map.entry(Pattern.compile("white|бял"), 1),
            Map.entry(Pattern.compile("pearl|перла"), 18),
            Map.entry(Pattern.compile("silver|сребр"), 13),
            Map.entry(Pattern.compile("dark gr[ae]y|charcoal|graphite|gun.?metal|тъмно сив"), 23),
            Map.entry(Pattern.compile("light gr[ae]y|светло сив"), 24),
            Map.entry(Pattern.compile("gr[ae]y|titan|сив"), 9),
            Map.entry(Pattern.compile("burgundy|wine|maroon|бордо"), 11),
            Map.entry(Pattern.compile("red|червен"), 4),
            Map.entry(Pattern.compile("navy|dark blue|тъмно син"), 22),
            Map.entry(Pattern.compile("light blue|sky blue|светло син"), 21),
            Map.entry(Pattern.compile("blue|син"), 6),
            Map.entry(Pattern.compile("dark green|тъмно зелен"), 26),
            Map.entry(Pattern.compile("green|olive|зелен"), 7),
            Map.entry(Pattern.compile("yellow|жълт"), 2),
            Map.entry(Pattern.compile("orange|оранжев"), 3),
            Map.entry(Pattern.compile("bronze|бронз"), 14),
            Map.entry(Pattern.compile("brown|кафяв"), 8),
            Map.entry(Pattern.compile("beige|sand|бежов"), 12),
            Map.entry(Pattern.compile("gold|злат"), 15),
            Map.entry(Pattern.compile("pink|розов"), 19),
            Map.entry(Pattern.compile("purple|lilac|лилав"), 16),
            Map.entry(Pattern.compile("violet|виолет"), 5));

    /** Our extras -> cars.bg's {@code e[]} checkbox ids. */
    static final Map<CarFeature, Integer> EXTRAS = new EnumMap<>(Map.ofEntries(
            Map.entry(CarFeature.AIR_CONDITIONING, 1),
            Map.entry(CarFeature.CLIMATE_CONTROL, 2),
            Map.entry(CarFeature.LEATHER_INTERIOR, 3),
            Map.entry(CarFeature.POWER_WINDOWS, 4),
            Map.entry(CarFeature.POWER_MIRRORS, 5),
            Map.entry(CarFeature.POWER_SEATS, 6),
            Map.entry(CarFeature.HEATED_SEATS, 7),
            Map.entry(CarFeature.SUNROOF, 8),
            Map.entry(CarFeature.ALLOY_WHEELS, 10),
            Map.entry(CarFeature.DVD_TV, 34),
            Map.entry(CarFeature.MULTIFUNCTION_STEERING_WHEEL, 35),
            Map.entry(CarFeature.FOUR_BY_FOUR, 11),
            Map.entry(CarFeature.ABS, 12),
            Map.entry(CarFeature.ESP, 13),
            Map.entry(CarFeature.DYNAMIC_STABILITY, 13),
            Map.entry(CarFeature.AIRBAGS_FRONT, 14),
            Map.entry(CarFeature.AIRBAGS_SIDE, 14),
            Map.entry(CarFeature.AIRBAGS_REAR, 14),
            Map.entry(CarFeature.XENON_HEADLIGHTS, 15),
            Map.entry(CarFeature.HALOGEN_HEADLIGHTS, 16),
            Map.entry(CarFeature.TRACTION_CONTROL, 17),
            Map.entry(CarFeature.PARKING_SENSORS, 18),
            Map.entry(CarFeature.ALARM, 19),
            Map.entry(CarFeature.CENTRAL_LOCKING, 21),
            Map.entry(CarFeature.CASCO, 22),
            Map.entry(CarFeature.ARMORED, 36),
            Map.entry(CarFeature.START_STOP, 42),
            Map.entry(CarFeature.KEYLESS_START, 43),
            Map.entry(CarFeature.MANUAL_SHIFT_MODE, 23),
            Map.entry(CarFeature.CRUISE_CONTROL, 24),
            Map.entry(CarFeature.POWER_STEERING, 25),
            Map.entry(CarFeature.ELECTRIC_POWER_STEERING, 25),
            Map.entry(CarFeature.TRIP_COMPUTER, 26),
            Map.entry(CarFeature.SERVICE_BOOK, 27),
            Map.entry(CarFeature.NAVIGATION, 30),
            Map.entry(CarFeature.RIGHT_HAND_DRIVE, 31),
            Map.entry(CarFeature.TUNING, 32),
            Map.entry(CarFeature.PANORAMIC_ROOF, 33),
            Map.entry(CarFeature.TAXI, 37),
            Map.entry(CarFeature.TOW_BAR, 39),
            Map.entry(CarFeature.SEVEN_SEATS, 40),
            Map.entry(CarFeature.REFRIGERATED, 41)));

    private static final Pattern YEAR_MONTH = Pattern.compile("^((?:19|20)\\d{2})-(\\d{2})");
    private static final Pattern METALLIC = Pattern.compile("metallic|pearl|металик|перла");

    private CarsBgMapper() {
    }

    /** Without the admins' choices (tests). */
    public static Result map(Car car, CarsBgCatalog catalog, int maxYear) {
        CarSpecs s = car.specs();
        return map(car, ModelMatcher.match(catalog.toSiteCatalog(), s.make(), s.model(), s.trim(), Optional.empty()),
                maxYear);
    }

    /** @param maxYear the last production year cars.bg offers (this year) */
    public static Result map(Car car, ModelMatch match, int maxYear) {
        CarSpecs s = car.specs();
        List<Warning> warnings = new ArrayList<>();
        List<CarFeature> features = s.features() == null ? List.of() : s.features();

        switch (match.status()) {
            case NO_CATALOG -> warnings.add(new Warning(Warning.Code.CATALOG_MISSING, null));
            case BRAND_MISSING -> warnings.add(new Warning(Warning.Code.BRAND_NOT_FOUND, s.make()));
            case MODEL_MISSING -> warnings.add(new Warning(Warning.Code.MODEL_NOT_FOUND, s.model()));
            case UNAVAILABLE -> warnings.add(new Warning(Warning.Code.MODEL_UNAVAILABLE, s.model()));
            case SUGGESTED -> warnings.add(new Warning(Warning.Code.MODEL_SUGGESTED, match.model().name()));
            case AUTO, MANUAL -> {
            }
        }
        boolean brand = match.brand() != null && match.status() != ModelMatch.Status.UNAVAILABLE;
        boolean model = brand && match.model() != null;

        Integer category = null;
        if (s.bodyType() != null) {
            String b = s.bodyType().toLowerCase(Locale.ROOT);
            category = CATEGORIES.stream().filter(e -> e.getKey().matcher(b).find()).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (category != null && category == 9 && MPV.matcher(b).find()) {
                warnings.add(new Warning(Warning.Code.CATEGORY_MPV, s.bodyType()));
            }
        }
        if (category == null) {
            warnings.add(new Warning(Warning.Code.CATEGORY_UNKNOWN, s.bodyType()));
        }

        Integer color = color(s.color());
        if (color == null && s.color() != null) {
            warnings.add(new Warning(Warning.Code.COLOR_UNKNOWN, s.color()));
        }
        boolean metallic = features.contains(CarFeature.METALLIC_PAINT)
                || (s.color() != null && METALLIC.matcher(s.color().toLowerCase(Locale.ROOT)).find());

        Integer doors = null;
        if (s.doors() != null) {
            doors = s.doors() <= 3 ? 1 : 2;
        } else if (features.contains(CarFeature.DOORS_2_3)) {
            doors = 1;
        } else if (features.contains(CarFeature.DOORS_4_5)) {
            doors = 2;
        } else {
            warnings.add(new Warning(Warning.Code.DOORS_UNKNOWN, null));
        }

        // the first registration when the source gives it, else the model year; cars.bg's list ends this year
        Integer month = null;
        Integer year = s.year();
        Matcher ym = s.firstRegistration() == null ? null : YEAR_MONTH.matcher(s.firstRegistration());
        if (ym != null && ym.find()) {
            int m = Integer.parseInt(ym.group(2));
            if (m >= 1 && m <= 12) {
                month = m;
                year = Integer.valueOf(ym.group(1));
            }
        }
        if (year != null && year > maxYear) {
            warnings.add(new Warning(Warning.Code.YEAR_ADJUSTED, String.valueOf(year)));
            year = maxYear;
        }
        if (month == null) {
            warnings.add(new Warning(Warning.Code.PRODUCTION_MONTH_UNKNOWN, null));
        }

        BigDecimal price = car.totalPriceEur() == null ? null
                : car.totalPriceEur().setScale(0, RoundingMode.HALF_UP);
        if (price == null) {
            warnings.add(new Warning(Warning.Code.NO_PRICE, null));
        }

        int condition = features.contains(CarFeature.FOR_PARTS) ? 3 : features.contains(CarFeature.CRASHED) ? 2 : 1;
        TreeSet<Integer> extras = new TreeSet<>();
        features.forEach(f -> {
            Integer id = EXTRAS.get(f);
            if (id != null) {
                extras.add(id);
            }
        });

        CarsBgDraft draft = new CarsBgDraft(
                brand ? match.brand().id() : null,
                brand ? match.brand().name() : null,
                model ? match.model().id() : null,
                model ? match.model().name() : null,
                engine(s.model(), s.trim(), model ? match.model().name() : null),
                fullName(s),
                category,
                fuel(s.fuelType(), features),
                s.transmission() == null ? null : switch (s.transmission()) {
                    case MANUAL -> 1;
                    case AUTOMATIC -> 2;
                },
                s.powerHp(),
                s.fuelType() == FuelType.ELECTRIC ? null : s.engineCc(),
                year,
                month,
                s.mileageKm(),
                doors,
                color,
                metallic,
                s.euroStandard() != null && s.euroStandard() >= 1 && s.euroStandard() <= 6 ? s.euroStandard() : null,
                price,
                3,
                1,
                condition,
                false,
                1,
                features.contains(CarFeature.LEASING),
                features.contains(CarFeature.BARTER),
                List.copyOf(extras));
        return new Result(draft, warnings);
    }

    static Integer fuel(FuelType fuel, List<CarFeature> features) {
        if (fuel == null) {
            return null;
        }
        return switch (fuel) {
            case PETROL -> features.contains(CarFeature.LPG) ? 3 : features.contains(CarFeature.CNG) ? 4 : 1;
            case DIESEL -> 2;
            case LPG -> 3;
            case HYBRID, PLUGIN_HYBRID, RANGE_EXTENDER -> 6;
            case ELECTRIC -> 7;
            case OTHER -> null;
        };
    }

    static Integer color(String color) {
        if (color == null) {
            return null;
        }
        String c = color.toLowerCase(Locale.ROOT);
        return COLORS.stream().filter(e -> e.getKey().matcher(c).find()).map(Map.Entry::getValue).findFirst()
                .orElse(null);
    }

    /**
     * Our model's name when cars.bg's is another ("Galaxy E5" posted as "EX5": the buyer searching our name still
     * sees it), else the trim; whole words, at most {@value #ENGINE_MAX} characters.
     */
    static String engine(String model, String trim, String siteModel) {
        boolean other = model != null && siteModel != null
                && !ModelNames.key(siteModel).equals(ModelNames.modelKey(model))
                && !ModelNames.key(siteModel).equals(ModelNames.key(model));
        return words(other ? model : trim, ENGINE_MAX);
    }

    static String fullName(CarSpecs s) {
        StringBuilder b = new StringBuilder();
        for (String part : new String[] {s.make(), s.model(), s.trim()}) {
            if (part != null && !part.isBlank()) {
                if (!b.isEmpty()) {
                    b.append(' ');
                }
                b.append(part.trim());
            }
        }
        return b.isEmpty() ? null : b.toString();
    }

    /** The first words that fit in {@code max} characters (cut inside the first word when it is longer). */
    static String words(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim().replaceAll("\\s+", " ");
        if (v.length() <= max) {
            return v;
        }
        int cut = v.lastIndexOf(' ', max);
        return (cut > 0 ? v.substring(0, cut) : v.substring(0, max)).trim();
    }
}
