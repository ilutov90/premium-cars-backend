package com.premiumcars.mobilebg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
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

/**
 * Fills mobile.bg's "Добави обява" form from a car, in mobile.bg's values. Brands and models are matched against
 * mobile.bg's own lists ({@link MobileBgCatalog}) when an admin's browser has sent them; what cannot be matched is
 * reported as a {@link Warning} and left to the admin. Pure: no database, no network.
 */
public final class MobileBgMapper {

    /** Problems the admin should look at; {@code value} is what the car has. The admin panel words them. */
    public record Warning(Code code, String value) {
        public enum Code {
            /** No mobile.bg lists yet: brand and model are the car's own, check them in the form. */
            CATALOG_MISSING,
            BRAND_NOT_FOUND,
            MODEL_NOT_FOUND,
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

    /** The car's make as the sources write it -> mobile.bg's brand. */
    private static final Map<String, String> BRAND_ALIASES = Map.ofEntries(
            Map.entry("mercedes", "Mercedes-Benz"),
            Map.entry("mercedesbenz", "Mercedes-Benz"),
            Map.entry("benz", "Mercedes-Benz"),
            Map.entry("volkswagen", "VW"),
            Map.entry("vw", "VW"),
            Map.entry("landrover", "Land Rover"),
            Map.entry("rangerover", "Land Rover"),
            Map.entry("chevy", "Chevrolet"),
            Map.entry("ssangyong", "SsangYong"),
            Map.entry("kgmobility", "SsangYong"),
            Map.entry("kgm", "SsangYong"),
            Map.entry("lynkco", "Lynk & Co"),
            Map.entry("liauto", "Li Auto"),
            Map.entry("lixiang", "Li Auto"));

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

    public static Result map(Car car, MobileBgCatalog catalog) {
        CarSpecs s = car.specs();
        List<Warning> warnings = new ArrayList<>();

        String brand = s.make();
        String model = s.model();
        if (catalog == null || catalog.isEmpty()) {
            warnings.add(new Warning(Warning.Code.CATALOG_MISSING, null));
        } else {
            Optional<MobileBgCatalog.Brand> b = brand(catalog, s.make());
            if (b.isEmpty()) {
                warnings.add(new Warning(Warning.Code.BRAND_NOT_FOUND, s.make()));
                brand = null;
                model = null;
            } else {
                brand = b.get().name();
                model = model(b.get(), s.model(), s.trim()).orElse(null);
                if (model == null) {
                    warnings.add(new Warning(Warning.Code.MODEL_NOT_FOUND, s.model()));
                }
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

    static Optional<MobileBgCatalog.Brand> brand(MobileBgCatalog catalog, String make) {
        if (make == null) {
            return Optional.empty();
        }
        String key = key(make);
        String alias = BRAND_ALIASES.get(key);
        for (MobileBgCatalog.Brand b : catalog.brands()) {
            if (key(b.name()).equals(key) || (alias != null && b.name().equalsIgnoreCase(alias))) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    /**
     * The model in mobile.bg's list: same name ignoring case, spaces and dashes ("SantaFe" = "Santa Fe"), else the
     * longest mobile.bg model the car's model (or model + trim) starts with ("Sportage R" -> "Sportage"), else one
     * that starts with the car's model.
     */
    static Optional<String> model(MobileBgCatalog.Brand brand, String model, String trim) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        String key = key(model);
        String full = key(model + " " + (trim == null ? "" : trim));
        String best = null;
        for (String m : brand.models()) {
            String k = key(m);
            if (k.isEmpty()) {
                continue;
            }
            if (k.equals(key)) {
                return Optional.of(m);
            }
            if ((key.startsWith(k) || full.startsWith(k)) && (best == null || k.length() > key(best).length())) {
                best = m;
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        return brand.models().stream().filter(m -> key(m).startsWith(key)).findFirst();
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

    /** "Santa Fe", "SantaFe" and "santa-fe" -> "santafe" */
    static String key(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        return v.length() > max ? v.substring(0, max).trim() : v;
    }
}
