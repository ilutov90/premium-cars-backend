package com.premiumcars.seo;

import java.text.Normalizer;
import java.util.Locale;

/** The address part of a make or model name on the SEO landing pages. */
public final class SeoSlugs {

    private SeoSlugs() {
    }

    /** Lower case, accents stripped, every run of other characters one "-", trimmed of "-"; "" when nothing is left. */
    public static String of(String name) {
        if (name == null) {
            return "";
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
