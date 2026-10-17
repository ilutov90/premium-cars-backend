package com.premiumcars.catalog.domain;

import java.util.Locale;

public enum FuelType {
    PETROL,
    DIESEL,
    HYBRID,
    PLUGIN_HYBRID,
    ELECTRIC,
    LPG,
    OTHER;

    /** Best-effort mapping from free text found on source pages (English, Chinese, Korean). */
    public static FuelType detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("plug-in") || t.contains("phev") || t.contains("插电") || t.contains("增程")
                || t.contains("range extend") || t.contains("erev")) {
            return PLUGIN_HYBRID;
        }
        if (t.contains("hybrid") || t.contains("hev") || t.contains("混动") || t.contains("油电")
                || t.contains("하이브리드")) {
            return HYBRID;
        }
        if (t.contains("electric") || t.contains(" ev") || t.startsWith("ev") || t.contains("bev")
                || t.contains("纯电") || t.contains("전기")) {
            return ELECTRIC;
        }
        if (t.contains("diesel") || t.contains("柴油") || t.contains("디젤")) {
            return DIESEL;
        }
        if (t.contains("lpg") || t.contains("lpi")) {
            return LPG;
        }
        if (t.contains("petrol") || t.contains("gasoline") || t.contains("汽油") || t.contains("가솔린")) {
            return PETROL;
        }
        return null;
    }
}
