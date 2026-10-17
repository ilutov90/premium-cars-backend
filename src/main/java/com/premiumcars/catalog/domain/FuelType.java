package com.premiumcars.catalog.domain;

import java.util.Locale;

public enum FuelType {
    PETROL,
    DIESEL,
    HYBRID,
    PLUGIN_HYBRID,
    /** Electric drive with a petrol generator (EREV), e.g. Li Auto, AITO. */
    RANGE_EXTENDER,
    ELECTRIC,
    LPG,
    OTHER;

    /** True for cars with a traction battery worth describing (capacity, electric range). */
    public boolean hasTractionBattery() {
        return this == ELECTRIC || this == PLUGIN_HYBRID || this == RANGE_EXTENDER;
    }

    /** Best-effort mapping from free text found on source pages (English, Chinese, Korean). */
    public static FuelType detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("range extend") || t.contains("range-extend") || t.contains("erev") || t.contains("增程")) {
            return RANGE_EXTENDER;
        }
        if (t.contains("plug-in") || t.contains("plug in") || t.contains("phev") || t.contains("插电")) {
            return PLUGIN_HYBRID;
        }
        if (t.contains("hybrid") || t.contains("hev") || t.contains("混动") || t.contains("油电")
                || t.contains("하이브리드")) {
            return HYBRID;
        }
        if (t.contains("electric") || t.contains(" ev") || t.startsWith("ev") || t.contains("bev")
                || t.contains("纯电") || t.contains("전기")) {
            // "Pure Electric" on autohome; "Electric" on autowini
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
