package com.premiumcars.mobilebg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.marketplace.ModelMatch;
import com.premiumcars.marketplace.ModelMatcher;

/**
 * Fills mobile.bg's "Добави обява" form from a car, in mobile.bg's values. The brand and model come from the
 * {@link ModelMatch} against mobile.bg's own lists ({@link MobileBgCatalog}, with the admins' choices); what cannot
 * be matched is reported as a {@link Warning} and left to the admin. Pure: no database, no network.
 */
public final class MobileBgMapper {

    /** Problems the admin should look at; {@code value} is what the car has. The admin panel words them. */
    public record Warning(Code code, String value) {
        public enum Code {
            /** No mobile.bg lists yet: brand and model are the car's own, check them in the form. */
            CATALOG_MISSING,
            BRAND_NOT_FOUND,
            MODEL_NOT_FOUND,
            /** The model was guessed (value: mobile.bg's): check it, or confirm it on the mapping page. */
            MODEL_SUGGESTED,
            /** An admin said mobile.bg has no model for it. */
            MODEL_UNAVAILABLE,
            /** mobile.bg has no "range extender": sent as plug-in hybrid. */
            RANGE_EXTENDER,
            CATEGORY_UNKNOWN,
            COLOR_UNKNOWN,
            /** The car's electric range is not WLTP (e.g. CLTC), but mobile.bg asks for WLTP. */
            RANGE_NOT_WLTP,
            PRODUCTION_MONTH_UNKNOWN,
            NO_PRICE
        }
    }

    public record Result(MobileBgDraft draft, List<Warning> warnings) {
    }

    static final List<String> MONTHS = List.of("януари", "февруари", "март", "април", "май", "юни", "юли", "август",
            "септември", "октомври", "ноември", "декември");

    /** Words in the source's body type -> mobile.bg's category (first match wins). */
    private static final List<Map.Entry<Pattern, String>> CATEGORIES = List.of(
            Map.entry(Pattern.compile("suv|crossover|jeep|off.?road|джип"), "Джип"),
            Map.entry(Pattern.compile("pick.?up|пикап"), "Пикап"),
            Map.entry(Pattern.compile("wagon|estate|touring|комби"), "Комби"),
            Map.entry(Pattern.compile("mpv|minivan|mini.?van|миниван"), "Миниван"),
            Map.entry(Pattern.compile("\\bvan\\b|\\bbus\\b|ван"), "Ван"),
            Map.entry(Pattern.compile("convertible|cabrio|roadster|кабрио"), "Кабрио"),
            Map.entry(Pattern.compile("coupe|купе"), "Купе"),
            Map.entry(Pattern.compile("hatch|liftback|хечбек"), "Хечбек"),
            Map.entry(Pattern.compile("sedan|saloon|седан"), "Седан"));

    /** Source colours (English) -> mobile.bg's colour, as its select writes it. */
    private static final List<Map.Entry<Pattern, String>> COLORS = List.of(
            Map.entry(Pattern.compile("black|черен"), "Черен"), // before "pearl": "Black Pearl" is black
            Map.entry(Pattern.compile("pearl|white|бял"), "Бял"),
            Map.entry(Pattern.compile("silver|сребр"), "Сребърен"),
            Map.entry(Pattern.compile("gr[ae]y|charcoal|graphite|gun.?metal|titan|сив"), "Сив"),
            Map.entry(Pattern.compile("red|burgundy|wine|maroon|червен"), "Червен"),
            Map.entry(Pattern.compile("blue|navy|син"), "Син"),
            Map.entry(Pattern.compile("green|olive|зелен"), "Зелен"),
            Map.entry(Pattern.compile("yellow|жълт"), "Жълт"),
            Map.entry(Pattern.compile("orange|оранжев"), "Оранжев"),
            Map.entry(Pattern.compile("brown|кафяв"), "Кафяв"),
            Map.entry(Pattern.compile("beige|sand|бежов"), "Бежов"),
            Map.entry(Pattern.compile("gold|златист"), "Златист"),
            Map.entry(Pattern.compile("purple|violet|лилав|виолет"), "Виолетов"));

    private static final Pattern YEAR_MONTH = Pattern.compile("^((?:19|20)\\d{2})-(\\d{2})");

    private MobileBgMapper() {
    }

    /** Without the admins' choices (tests). */
    public static Result map(Car car, MobileBgCatalog catalog) {
        CarSpecs s = car.specs();
        return map(car, catalog, ModelMatcher.match(catalog == null ? null : catalog.toSiteCatalog(), s.make(),
                s.model(), s.trim(), Optional.empty()));
    }

    /** @param match the car's make + model in mobile.bg's lists (with the admins' choices) */
    public static Result map(Car car, MobileBgCatalog catalog, ModelMatch match) {
        CarSpecs s = car.specs();
        List<Warning> warnings = new ArrayList<>();

        // without mobile.bg's lists the car's own names are sent; else mobile.bg's or none
        String brand = s.make();
        String model = s.model();
        switch (match.status()) {
            case NO_CATALOG -> warnings.add(new Warning(Warning.Code.CATALOG_MISSING, null));
            case BRAND_MISSING -> {
                warnings.add(new Warning(Warning.Code.BRAND_NOT_FOUND, s.make()));
                brand = null;
                model = null;
            }
            case UNAVAILABLE -> {
                warnings.add(new Warning(Warning.Code.MODEL_UNAVAILABLE, s.model()));
                brand = null;
                model = null;
            }
            case MODEL_MISSING -> {
                warnings.add(new Warning(Warning.Code.MODEL_NOT_FOUND, s.model()));
                brand = match.brand().name();
                model = null;
            }
            case SUGGESTED -> {
                warnings.add(new Warning(Warning.Code.MODEL_SUGGESTED, match.model().name()));
                brand = match.brand().name();
                model = match.model().name();
            }
            case AUTO, MANUAL -> {
                brand = match.brand().name();
                model = match.model().name();
            }
        }

        String engineType = engineType(s.fuelType());
        if (s.fuelType() == FuelType.RANGE_EXTENDER) {
            warnings.add(new Warning(Warning.Code.RANGE_EXTENDER, null));
        }
        boolean battery = s.fuelType() == FuelType.ELECTRIC || s.fuelType() == FuelType.PLUGIN_HYBRID
                || s.fuelType() == FuelType.RANGE_EXTENDER;
        Integer range = battery ? s.electricRangeKm() : null;
        if (range != null && s.rangeStandard() != null && !s.rangeStandard().equalsIgnoreCase("WLTP")) {
            warnings.add(new Warning(Warning.Code.RANGE_NOT_WLTP, s.rangeStandard()));
        }

        String category = category(s.bodyType());
        if (category == null) {
            warnings.add(new Warning(Warning.Code.CATEGORY_UNKNOWN, s.bodyType()));
        }
        String color = color(s.color(), catalog);
        if (color == null && s.color() != null) {
            warnings.add(new Warning(Warning.Code.COLOR_UNKNOWN, s.color()));
        }

        // "Дата на производство": the first registration when the source gives it, else the model year
        String month = null;
        Integer year = s.year();
        Matcher ym = s.firstRegistration() == null ? null : YEAR_MONTH.matcher(s.firstRegistration());
        if (ym != null && ym.find()) {
            int m = Integer.parseInt(ym.group(2));
            if (m >= 1 && m <= 12) {
                month = MONTHS.get(m - 1);
                year = Integer.valueOf(ym.group(1));
            }
        }
        if (month == null) {
            warnings.add(new Warning(Warning.Code.PRODUCTION_MONTH_UNKNOWN, null));
        }

        BigDecimal price = car.totalPriceEur() == null ? null
                : car.totalPriceEur().setScale(0, RoundingMode.HALF_UP);
        if (price == null) {
            warnings.add(new Warning(Warning.Code.NO_PRICE, null));
        }

        MobileBgDraft draft = new MobileBgDraft(
                brand,
                model,
                cut(s.trim(), 50),
                engineType,
                "0",
                s.powerHp(),
                s.euroStandard(),
                s.transmission() == null ? null : switch (s.transmission()) {
                    case AUTOMATIC -> "Автоматична";
                    case MANUAL -> "Ръчна";
                },
                category,
                s.fuelType() == FuelType.ELECTRIC ? null : s.engineCc(),
                price,
                "2",
                "EUR",
                s.mileageKm(),
                month,
                year,
                color,
                "София",
                "гр. София",
                range,
                battery ? s.batteryKwh() : null,
                s.features());
        return new Result(draft, warnings);
    }

    static String engineType(FuelType fuel) {
        if (fuel == null) {
            return null;
        }
        return switch (fuel) {
            case PETROL -> "Бензинов";
            case DIESEL -> "Дизелов";
            case HYBRID -> "Хибриден";
            case PLUGIN_HYBRID, RANGE_EXTENDER -> "Plug-in хибрид";
            case ELECTRIC -> "Електрически";
            case LPG -> "Газ";
            case OTHER -> null;
        };
    }

    static String category(String bodyType) {
        if (bodyType == null) {
            return null;
        }
        String b = bodyType.toLowerCase(Locale.ROOT);
        return CATEGORIES.stream().filter(e -> e.getKey().matcher(b).find()).map(Map.Entry::getValue).findFirst()
                .orElse(null);
    }

    /** mobile.bg's colour for the car's: from its list when there is one (same start of word), else our guess. */
    static String color(String color, MobileBgCatalog catalog) {
        if (color == null) {
            return null;
        }
        String c = color.toLowerCase(Locale.ROOT);
        String guess = COLORS.stream().filter(e -> e.getKey().matcher(c).find()).map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (guess == null) {
            return null;
        }
        List<MobileBgCatalog.Option> options = catalog == null ? List.of() : catalog.options("f17");
        if (options.isEmpty()) {
            return guess;
        }
        String stem = guess.substring(0, Math.min(4, guess.length())).toLowerCase(Locale.ROOT);
        return options.stream()
                .map(o -> o.value() != null && !o.value().isBlank() ? o.value() : o.text())
                .filter(v -> v != null && v.toLowerCase(Locale.ROOT).startsWith(stem))
                .findFirst()
                .orElse(null);
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        return v.length() > max ? v.substring(0, max).trim() : v;
    }
}
