package com.premiumcars.catalog.domain;

import java.util.Locale;

public enum Drivetrain {
    FWD,
    RWD,
    AWD;

    /** Best-effort mapping from source text such as "Front-Wheel Drive (FWD)", "4WD", "Quattro", "前驱". */
    public static Drivetrain detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("awd") || t.contains("4wd") || t.contains("all-wheel") || t.contains("all wheel")
                || t.contains("four-wheel") || t.contains("four wheel") || t.contains("4x4")
                || t.contains("quattro") || t.contains("xdrive") || t.contains("4matic") || t.contains("四驱")) {
            return AWD;
        }
        if (t.contains("rwd") || t.contains("rear") || t.contains("后驱") || t.contains("후륜")) {
            return RWD;
        }
        if (t.contains("fwd") || t.contains("front") || t.contains("前驱") || t.contains("전륜")) {
            return FWD;
        }
        return null;
    }
}
