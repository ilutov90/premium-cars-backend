package com.premiumcars.marketplace;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How our makes and models are compared with a listing site's: keys without case, spaces, dashes or accents
 * ("K3" = "K 3", "7X" = "7 X"), models without the sources' powertrain and edition words ("Song PLUS New Energy" =
 * "Song PLUS", "Seal 07 DM-i" = "Seal 07"), the sites' other spellings of some makes, and the export names of
 * Chinese models (offered as suggestions only).
 */
public final class ModelNames {

    private ModelNames() {
    }

    /** "Santa Fe", "SantaFe" and "santa-fe" -> "santafe". */
    public static String key(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Words the sources add to a model that no listing site has in its list. */
    private static final Pattern NOISE = Pattern.compile(
            "\\([^)]*\\)"                                              // "(Chinese Domestic Model)"
            + "|\\bnew energy\\b|\\bextended range electric version\\b|\\bintelligent engine\\b"
            + "|\\b(?:dm|em)-?[ip]\\b|\\b(?:phev|phv|hev|ev|erev)\\b"
            + "|\\b\\d+(?:st|nd|rd|th) gen\\b",
            Pattern.CASE_INSENSITIVE);

    /** The model's key without the sources' extra words ("Seal 06 DM-i" -> "seal06"). */
    public static String modelKey(String model) {
        if (model == null) {
            return "";
        }
        String k = key(NOISE.matcher(model).replaceAll(" "));
        return k.isEmpty() ? key(model) : k;
    }

    /** Our make's key -> how each site writes it (when not the same key). */
    private static final Map<String, Map<Site, String>> MAKE_ALIASES = Map.ofEntries(
            Map.entry("mercedes", both("Mercedes-Benz")),
            Map.entry("mercedesbenz", both("Mercedes-Benz")),
            Map.entry("benz", both("Mercedes-Benz")),
            Map.entry("volkswagen", both("VW")),
            Map.entry("chevy", both("Chevrolet")),
            Map.entry("kgmobility", both("SsangYong")),
            Map.entry("kgm", both("SsangYong")),
            Map.entry("lynkco", Map.of(Site.MOBILE_BG, "Lynk & Co", Site.CARS_BG, "Lynk and Co")),
            Map.entry("lynkandco", Map.of(Site.MOBILE_BG, "Lynk & Co", Site.CARS_BG, "Lynk and Co")),
            Map.entry("lixiang", both("Li Auto")),
            Map.entry("geelyauto", both("Geely")),
            Map.entry("geelygalaxy", both("Geely")),
            Map.entry("dongfengaeolus", both("Dongfeng")),
            Map.entry("xiaomiauto", both("Xiaomi")),
            Map.entry("rangerover", Map.of(Site.MOBILE_BG, "Land Rover", Site.CARS_BG, "Range Rover")));

    private static Map<Site, String> both(String name) {
        return Map.of(Site.MOBILE_BG, name, Site.CARS_BG, name);
    }

    /** How the site may write our make, besides the make itself; null when no other spelling is known. */
    public static String makeAlias(Site site, String make) {
        Map<Site, String> a = MAKE_ALIASES.get(key(make));
        return a == null ? null : a.get(site);
    }

    /**
     * Export names of Chinese models (make key + model key -> the names the sites may use), suggested to the admin
     * and never chosen alone: "BYD Yuan UP" is sold in Europe as "Atto 2".
     */
    private static final Map<String, List<String>> EXPORT_NAMES = Map.ofEntries(
            Map.entry("byd|yuanup", List.of("Atto 2")),
            Map.entry("byd|yuanplus", List.of("Atto 3")),
            Map.entry("byd|songplus", List.of("Seal U")),
            Map.entry("byd|seal06", List.of("Seal 6")),
            Map.entry("byd|sealion07", List.of("Sealion 7")),
            Map.entry("geely|galaxye5", List.of("EX5")),
            Map.entry("geely|e5", List.of("EX5")),
            Map.entry("geely|xingyuel", List.of("Monjaro")),
            Map.entry("geely|monjarol", List.of("Monjaro")));

    public static List<String> exportNames(String make, String model) {
        String m = key(make);
        String alias = makeAlias(Site.CARS_BG, make);
        String base = alias != null ? key(alias) : m;
        return EXPORT_NAMES.getOrDefault(base + "|" + modelKey(model), List.of());
    }
}
