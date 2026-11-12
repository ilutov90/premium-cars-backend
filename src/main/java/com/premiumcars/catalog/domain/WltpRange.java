package com.premiumcars.catalog.domain;

/**
 * The electric range on the WLTP cycle (what mobile.bg asks for), from the range the source gives. Chinese sources
 * give CLTC, which is about a fifth more generous: WLTP = CLTC × 0.82 (the usual rule, e.g. BYD Seal 700 km CLTC /
 * 570 km WLTP), rounded to 5 km. Not stored: it follows the car's range and standard.
 */
public final class WltpRange {

    static final double CLTC_TO_WLTP = 0.82;

    private WltpRange() {
    }

    /** The WLTP range; null when there is no range or its standard is neither WLTP nor CLTC. */
    public static Integer km(Integer rangeKm, String standard) {
        if (rangeKm == null || standard == null) {
            return null;
        }
        String s = standard.trim();
        if (s.equalsIgnoreCase("WLTP")) {
            return rangeKm;
        }
        if (s.equalsIgnoreCase("CLTC")) {
            return (int) (Math.round(rangeKm * CLTC_TO_WLTP / 5.0) * 5);
        }
        return null;
    }
}
