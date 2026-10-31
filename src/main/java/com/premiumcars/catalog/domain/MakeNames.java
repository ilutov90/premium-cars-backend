package com.premiumcars.catalog.domain;

import java.util.Locale;

/**
 * One name per make (October 31, 2026): the sources split some makes into sub-brands ("Geely Auto" and "Geely
 * Galaxy", "Dongfeng Aeolus", "Xiaomi Auto"); our catalog keeps the make the market knows (Geely, Dongfeng, Xiaomi),
 * so the site's filter shows it once and mobile.bg / cars.bg find it. Galaxy stays in the model ("Galaxy E5").
 * Applied to every import; migration 031 applied it to the cars already in the catalog.
 */
public final class MakeNames {

    /** The make and model as the catalog keeps them. */
    public record Name(String make, String model) {
    }

    private MakeNames() {
    }

    public static Name canonical(String make, String model) {
        if (make == null) {
            return new Name(null, model);
        }
        String m = make.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return switch (m) {
            case "geely auto" -> new Name("Geely", model);
            case "geely galaxy" -> new Name("Geely", model == null || model.isBlank()
                    || model.trim().toLowerCase(Locale.ROOT).startsWith("galaxy") ? model : "Galaxy " + model.trim());
            case "dongfeng aeolus", "dongfeng fengshen" -> new Name("Dongfeng", model);
            case "xiaomi auto" -> new Name("Xiaomi", model);
            default -> new Name(make, model);
        };
    }
}
