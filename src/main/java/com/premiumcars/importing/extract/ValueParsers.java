package com.premiumcars.importing.extract;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, forgiving parsers for numbers as they appear on car listing pages. */
public final class ValueParsers {

    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3}(?:[,\\s]\\d{3})+|\\d+)(?:\\.(\\d+))?");
    private static final Pattern YEAR = Pattern.compile("\\b(19[89]\\d|20[0-4]\\d)\\b");
    /** Chinese "wan" = 10,000. Prices like "12.5万" and mileage like "3.2万公里" use it. */
    private static final String WAN = "万";

    private ValueParsers() {
    }

    public static BigDecimal parseNumber(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return null;
        }
        String integer = m.group(1).replaceAll("[,\\s]", "");
        String fraction = m.group(2);
        BigDecimal value = new BigDecimal(fraction == null ? integer : integer + "." + fraction);
        if (text.contains(WAN)) {
            value = value.multiply(BigDecimal.valueOf(10_000));
        }
        return value;
    }

    public static Integer parseYear(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = YEAR.matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /**
     * Mileage in km. Plain numbers are taken as km, "3.2万公里" as 32,000 km,
     * and "12,000 miles" is converted to km.
     */
    public static Integer parseMileageKm(String text) {
        BigDecimal value = parseNumber(text);
        if (value == null) {
            return null;
        }
        if (text.toLowerCase().contains("mile")) {
            value = value.multiply(new BigDecimal("1.609344"));
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(2_000_000)) > 0) {
            return null;
        }
        return value.intValue();
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!isBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
