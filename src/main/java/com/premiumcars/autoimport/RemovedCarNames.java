package com.premiumcars.autoimport;

import java.text.Normalizer;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.catalog.domain.MakeNames;
import com.premiumcars.marketplace.ModelNames;

/**
 * Names a deleted car ({@code auto_import_removed}) by the source site's own menus: the make as the site's list
 * ({@code auto_import_catalog} / {@code auto_import_brand}) writes it, the model as that make's menu writes it.
 * Never a make outside the source's lists. Pure: the caller hands it the source's makes ({@link #makes}).
 * <ul>
 *   <li>{@link #fromCar}: at removal time, from the listing row's make code and menu model, else the car's make and
 *       model, else its title and slug;</li>
 *   <li>{@link #fromText}: rows noted before 039, of which only the title and slug are left: the make whose name (or
 *       {@link MakeNames} alias) is found as whole words in the title or the slug, the longest name winning ("Land
 *       Rover" beats "Rover"); the longest menu model of that make found as whole words in the title (after the
 *       make), else in the slug; the year from the slug (built by {@code Slugs.forCar}: "make-model-year-id"),
 *       else the title.</li>
 * </ul>
 */
public final class RemovedCarNames {

    private static final int FIRST_YEAR = 1990;

    /** A make of the source's lists with its menu models (all the site's, plus the chosen ones). */
    public record Make(String code, String name, List<String> models) {

        public Make {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    /** What could be named; any part may be null. */
    public record Names(String make, String model, Integer year) {

        public static final Names NONE = new Names(null, null, null);
    }

    private RemovedCarNames() {
    }

    /** The source's makes: its whole list, with the makes to import (and their chosen models) added. */
    public static List<Make> makes(List<CatalogBrand> catalog, List<Brand> brands) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Set<String>> models = new LinkedHashMap<>();
        for (CatalogBrand b : catalog) {
            names.putIfAbsent(b.code(), b.name());
            Set<String> m = models.computeIfAbsent(b.code(), k -> new LinkedHashSet<>());
            if (b.models() != null) {
                b.models().stream().map(CatalogModel::name).filter(RemovedCarNames::present).forEach(m::add);
            }
        }
        for (Brand b : brands) {
            names.putIfAbsent(b.code(), b.name());
            Set<String> m = models.computeIfAbsent(b.code(), k -> new LinkedHashSet<>());
            b.models().stream().map(CatalogModel::name).filter(RemovedCarNames::present).forEach(m::add);
        }
        List<Make> out = new ArrayList<>();
        names.forEach((code, name) -> {
            if (present(name)) {
                out.add(new Make(code, name.trim(), new ArrayList<>(models.get(code))));
            }
        });
        return out;
    }

    /**
     * A car being deleted.
     *
     * @param brandCode the listing row's make code (null without a row)
     * @param menuModel the listing row's menu model (null without one)
     */
    public static Names fromCar(String brandCode, String menuModel, String carMake, String carModel, Integer carYear,
                                String title, String slug, List<Make> makes) {
        return fromCar(brandCode, menuModel, carMake, carModel, carYear, title, slug, makes, thisYear());
    }

    static Names fromCar(String brandCode, String menuModel, String carMake, String carModel, Integer carYear,
                         String title, String slug, List<Make> makes, int thisYear) {
        Make make = null;
        if (present(brandCode)) {
            make = makes.stream().filter(m -> brandCode.equals(m.code())).findFirst().orElse(null);
        }
        if (make == null && present(carMake)) {
            make = byName(carMake, carModel, makes);
        }
        if (make == null) {
            String named = fromText(title, slug, makes, thisYear).make();
            make = named == null ? null
                    : makes.stream().filter(m -> m.name().equals(named)).findFirst().orElse(null);
        }
        String model;
        if (present(menuModel)) {
            model = menuModel.trim();
        } else {
            String menu = make == null ? null : menuModelOf(make, carModel);
            if (menu != null) {
                model = menu;
            } else if (present(carModel)) {
                model = carModel.trim();
            } else if (make != null) {
                model = model(make, words(title), words(slug));
            } else {
                model = null;
            }
        }
        Integer year = carYear != null ? carYear : year(title, slug, thisYear);
        return new Names(make == null ? null : make.name(), model, year);
    }

    /** A row of which only the title and slug are left. */
    public static Names fromText(String title, String slug, List<Make> makes) {
        return fromText(title, slug, makes, thisYear());
    }

    static Names fromText(String title, String slug, List<Make> makes, int thisYear) {
        String t = words(title);
        String s = words(slug);
        Make best = null;
        int bestLength = 0;
        boolean bestHasModel = false;
        for (Make m : makes) {
            int length = 0;
            for (String form : makeForms(m)) {
                if (form.length() > length && (contains(t, form) >= 0 || contains(s, form) >= 0)) {
                    length = form.length();
                }
            }
            if (length == 0 || length < bestLength) {
                continue;
            }
            boolean hasModel = model(m, t, s) != null;
            if (length > bestLength || (hasModel && !bestHasModel)) {
                best = m;
                bestLength = length;
                bestHasModel = hasModel;
            }
        }
        Integer year = year(title, slug, thisYear);
        if (best == null) {
            return new Names(null, null, year);
        }
        return new Names(best.name(), model(best, t, s), year);
    }

    // ---------------------------------------------------------------- makes

    /** The car's make against the source's names (and their aliases); several = the one with the car's model. */
    private static Make byName(String carMake, String carModel, List<Make> makes) {
        String k = words(carMake);
        List<Make> exact = new ArrayList<>();
        List<Make> alias = new ArrayList<>();
        for (Make m : makes) {
            if (words(m.name()).equals(k)) {
                exact.add(m);
            } else if (makeForms(m).contains(k)) {
                alias.add(m);
            }
        }
        if (!exact.isEmpty()) {
            return exact.getFirst();
        }
        if (alias.isEmpty()) {
            return null;
        }
        return alias.stream().filter(m -> menuModelOf(m, carModel) != null).findFirst().orElse(alias.getFirst());
    }

    /** The make's name and the catalog's one name for it ("Geely Galaxy" -> "geely galaxy", "geely"). */
    private static Set<String> makeForms(Make m) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(words(m.name()));
        forms.add(words(MakeNames.canonical(m.name(), null).make()));
        forms.remove("");
        return forms;
    }

    // ---------------------------------------------------------------- models

    /** The menu model the car's own model text names: the same name, the same without powertrain words, or within. */
    private static String menuModelOf(Make make, String carModel) {
        if (!present(carModel)) {
            return null;
        }
        String key = ModelNames.key(carModel);
        for (String m : make.models()) {
            if (ModelNames.key(m).equals(key)) {
                return m;
            }
        }
        String stripped = ModelNames.modelKey(carModel);
        for (String m : make.models()) {
            if (ModelNames.modelKey(m).equals(stripped)) {
                return m;
            }
        }
        return longestIn(make, words(carModel));
    }

    /**
     * The make's model in the title (after the make), else in the slug, else written together in the title
     * ("SantaFe" = "Santa Fe": only models of {@value #MIN_JOINED} letters or more, shorter ones match too easily).
     */
    private static String model(Make make, String title, String slug) {
        String found = longestIn(make, afterMake(make, title));
        if (found == null) {
            found = longestIn(make, afterMake(make, slug));
        }
        return found != null ? found : joinedIn(make, afterMake(make, title).replace(" ", ""));
    }

    private static final int MIN_JOINED = 5;

    private static String joinedIn(Make make, String joined) {
        String best = null;
        for (String m : make.models()) {
            String k = words(m).replace(" ", "");
            if (k.length() >= MIN_JOINED && joined.contains(k)
                    && (best == null || k.length() > words(best).replace(" ", "").length())) {
                best = m;
            }
        }
        return best;
    }

    private static String afterMake(Make make, String text) {
        int best = -1;
        int end = 0;
        for (String form : makeForms(make)) {
            int at = contains(text, form);
            if (at >= 0 && (best < 0 || at < best || (at == best && at + form.length() > end))) {
                best = at;
                end = at + form.length();
            }
        }
        return best < 0 ? text : text.substring(Math.min(end, text.length()));
    }

    /** The longest menu model (or its name without powertrain words) found as whole words; a tie: the first one. */
    private static String longestIn(Make make, String text) {
        if (text.isEmpty()) {
            return null;
        }
        String best = null;
        int bestLength = 0;
        int bestAt = Integer.MAX_VALUE;
        for (String m : make.models()) {
            for (String form : new LinkedHashSet<>(List.of(words(m), words(ModelNames.stripped(m))))) {
                if (form.isEmpty()) {
                    continue;
                }
                int at = contains(text, form);
                if (at < 0) {
                    continue;
                }
                if (form.length() > bestLength || (form.length() == bestLength && at < bestAt)) {
                    best = m;
                    bestLength = form.length();
                    bestAt = at;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- year

    /** A year in the slug (before its trailing id), else the first one in the title. */
    static Integer year(String title, String slug, int thisYear) {
        if (present(slug)) {
            String[] parts = slug.trim().toLowerCase(Locale.ROOT).split("-+");
            int last = parts.length > 1 && parts[parts.length - 1].matches("\\d+") ? parts.length - 2 : parts.length - 1;
            for (int i = last; i >= 0; i--) {
                Integer y = asYear(parts[i], thisYear);
                if (y != null) {
                    return y;
                }
            }
        }
        for (String part : words(title).split(" ")) {
            Integer y = asYear(part, thisYear);
            if (y != null) {
                return y;
            }
        }
        return null;
    }

    private static Integer asYear(String token, int thisYear) {
        if (!token.matches("\\d{4}")) {
            return null;
        }
        int y = Integer.parseInt(token);
        return y >= FIRST_YEAR && y <= thisYear + 1 ? y : null;
    }

    // ---------------------------------------------------------------- text

    /** Lower-case words without accents, separated by one space: "BYD Seal 06 DM-i" -> "byd seal 06 dm i". */
    static String words(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Where {@code form} starts in {@code text} as whole words, or -1. */
    private static int contains(String text, String form) {
        if (form.isEmpty() || text.isEmpty()) {
            return -1;
        }
        return (" " + text + " ").indexOf(" " + form + " ");
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static int thisYear() {
        return Year.now(ZoneId.of("Europe/Sofia")).getValue();
    }
}
