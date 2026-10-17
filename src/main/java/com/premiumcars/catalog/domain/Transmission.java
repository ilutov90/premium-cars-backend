package com.premiumcars.catalog.domain;

import java.util.Locale;

public enum Transmission {
    AUTOMATIC,
    MANUAL;

    /** Best-effort mapping from free text found on source pages. */
    public static Transmission detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("manual") || t.contains("手动") || t.contains("수동") || t.matches(".*\\bmt\\b.*")) {
            return MANUAL;
        }
        if (t.contains("auto") || t.contains("cvt") || t.contains("dct") || t.contains("dsg")
                || t.contains("dual-clutch") || t.contains("dual clutch") || t.contains("single-speed")
                || t.contains("single speed") || t.contains("fixed gear") || t.contains("e-cvt")
                || t.contains("自动") || t.contains("오토") || t.contains("자동") || t.matches(".*\\bat\\b.*")) {
            return AUTOMATIC;
        }
        return null;
    }
}
