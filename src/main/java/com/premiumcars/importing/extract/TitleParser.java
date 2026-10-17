package com.premiumcars.importing.extract;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a listing title into make, model and trim:
 * "BYD Han 2020 EV Extended Range Premium" -> make "BYD", model "Han", trim "EV Extended Range Premium".
 * Makes made of several words ("Li Auto", "Great Wall") must be listed in {@link #KNOWN_MAKES}.
 */
public final class TitleParser {

    public record Parts(String make, String model, String trim) {
    }

    /** Longest first, so "Geely Galaxy" wins over "Geely". */
    static final List<String> KNOWN_MAKES = List.of(
            "Mercedes-Benz", "Mercedes-AMG", "Land Rover", "Range Rover", "Rolls-Royce", "Aston Martin",
            "Alfa Romeo", "Great Wall", "Li Auto", "Lynk & Co", "Lynk&Co", "GAC Trumpchi", "GAC Aion",
            "Geely Galaxy", "KG Mobility", "Renault Korea", "Leapmotor", "Mini", "Tank", "BYD", "Denza", "Geely",
            "Zeekr", "Chery", "Haval", "NIO", "XPeng", "Changan", "Hongqi", "AITO", "Voyah", "Xiaomi", "Jetour",
            "Exeed", "Wuling", "Baojun", "Neta", "Deepal", "Avatr", "Jaecoo", "Omoda", "Lantu", "Yangwang",
            "Fangchengbao", "IM", "Smart", "Polestar", "Hyundai", "Kia", "Genesis", "SsangYong"
    ).stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();

    private static final Pattern YEAR = Pattern.compile("\\s(?:19|20)\\d{2}\\b");

    private TitleParser() {
    }

    public static Parts split(String title) {
        if (title == null || title.isBlank()) {
            return new Parts(null, null, null);
        }
        String t = title.replaceAll("\\s+", " ").trim();
        String make = null;
        String lower = t.toLowerCase(Locale.ROOT);
        for (String mk : KNOWN_MAKES) {
            if (lower.startsWith(mk.toLowerCase(Locale.ROOT) + " ")) {
                make = t.substring(0, mk.length());
                break;
            }
        }
        if (make == null) {
            make = t.split(" ")[0];
        }
        String rest = t.substring(make.length()).trim();

        // Model = everything before the first year ("Tang New Energy 2019 DM ..." -> "Tang New Energy")
        Matcher y = YEAR.matcher(" " + rest);
        String model;
        String trim;
        if (y.find()) {
            model = (" " + rest).substring(0, y.start()).trim();
            trim = (" " + rest).substring(y.end()).trim();
        } else {
            String[] words = rest.split(" ");
            model = words.length > 0 ? words[0] : "";
            trim = rest.substring(model.length()).trim();
        }
        if (model.isEmpty()) {
            String[] words = rest.split(" ");
            model = String.join(" ", List.of(words).subList(0, Math.min(2, words.length)));
        }
        return new Parts(make, model.isEmpty() ? null : model, trim.isEmpty() ? null : trim);
    }
}
