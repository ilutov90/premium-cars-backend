package com.premiumcars.marketplace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.premiumcars.marketplace.ModelMatch.Status;
import com.premiumcars.marketplace.SiteCatalog.SiteBrand;
import com.premiumcars.marketplace.SiteCatalog.SiteModel;

/**
 * Matches one of our makes + models to a listing site's lists: an admin's choice first, then the same name (keys
 * of {@link ModelNames}), then suggestions (export names, one name starting with the other: "Seal 06" -> "Seal" is a
 * different car, so a suggestion is never taken alone). Pure: no database, no network.
 */
public final class ModelMatcher {

    private ModelMatcher() {
    }

    public static ModelMatch match(SiteCatalog catalog, String make, String model, Optional<ModelMapping> chosen) {
        return match(catalog, make, model, null, chosen);
    }

    /**
     * @param trim   the car's trim, when matching one car: a suggestion its model + trim starts with comes first
     *               ("GLE" + "350 4MATIC" -> "GLE 350")
     * @param chosen an admin's mapping for this make + model on this site, if any
     */
    public static ModelMatch match(SiteCatalog catalog, String make, String model, String trim,
                                   Optional<ModelMapping> chosen) {
        if (chosen.isPresent() && chosen.get().unavailable()) {
            return new ModelMatch(Status.UNAVAILABLE, null, null, List.of());
        }
        if (catalog == null || catalog.isEmpty()) {
            return new ModelMatch(Status.NO_CATALOG, null, null, List.of());
        }
        if (chosen.isPresent()) {
            ModelMapping m = chosen.get();
            Optional<SiteBrand> b = catalog.brands().stream()
                    .filter(x -> ModelNames.key(x.name()).equals(ModelNames.key(m.targetBrand()))).findFirst();
            Optional<SiteModel> mo = b.flatMap(x -> x.models().stream()
                    .filter(y -> ModelNames.key(y.name()).equals(ModelNames.key(m.targetModel()))).findFirst());
            if (mo.isPresent()) {
                return new ModelMatch(Status.MANUAL, b.get(), mo.get(), List.of());
            }
            // the chosen model is gone from the site's lists: match again as if nothing was chosen
        }
        Optional<SiteBrand> brand = brand(catalog, make);
        if (brand.isEmpty()) {
            return new ModelMatch(Status.BRAND_MISSING, null, null, List.of());
        }
        SiteBrand b = brand.get();
        String key = ModelNames.modelKey(model);
        if (!key.isEmpty()) {
            for (SiteModel m : b.models()) {
                String k = ModelNames.key(m.name());
                if (k.equals(key) || k.equals(ModelNames.key(model))) {
                    return new ModelMatch(Status.AUTO, b, m, List.of());
                }
            }
        }
        List<SiteModel> suggestions = suggestions(b, make, model, trim);
        if (!suggestions.isEmpty()) {
            return new ModelMatch(Status.SUGGESTED, b, suggestions.getFirst(), suggestions);
        }
        return new ModelMatch(Status.MODEL_MISSING, b, null, List.of());
    }

    static Optional<SiteBrand> brand(SiteCatalog catalog, String make) {
        if (make == null || make.isBlank()) {
            return Optional.empty();
        }
        String key = ModelNames.key(make);
        String alias = ModelNames.makeAlias(catalog.site(), make);
        String aliasKey = alias == null ? null : ModelNames.key(alias);
        return catalog.brands().stream()
                .filter(b -> ModelNames.key(b.name()).equals(key)).findFirst()
                .or(() -> aliasKey == null ? Optional.empty() : catalog.brands().stream()
                        .filter(b -> ModelNames.key(b.name()).equals(aliasKey)).findFirst());
    }

    /** Export names first, then models whose name starts with ours or ours with theirs (the longest first). */
    static List<SiteModel> suggestions(SiteBrand brand, String make, String model, String trim) {
        List<SiteModel> out = new ArrayList<>();
        for (String name : ModelNames.exportNames(make, model)) {
            brand.models().stream().filter(m -> ModelNames.key(m.name()).equals(ModelNames.key(name)))
                    .findFirst().ifPresent(out::add);
        }
        String key = ModelNames.modelKey(model);
        String full = trim == null || trim.isBlank() ? null : ModelNames.key(model + " " + trim);
        if (key.length() >= 2) {
            brand.models().stream()
                    .filter(m -> {
                        String k = ModelNames.key(m.name());
                        return k.length() >= 2 && !k.equals(key) && (key.startsWith(k) || k.startsWith(key));
                    })
                    .sorted(Comparator.comparing((SiteModel m) -> full == null
                                    || !full.startsWith(ModelNames.key(m.name())))
                            .thenComparingInt(m -> -ModelNames.key(m.name()).length()))
                    .filter(m -> !out.contains(m))
                    .forEach(out::add);
        }
        return out.size() > 5 ? out.subList(0, 5) : out;
    }
}
