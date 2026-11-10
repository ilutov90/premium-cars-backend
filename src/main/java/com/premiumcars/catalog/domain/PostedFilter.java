package com.premiumcars.catalog.domain;

/**
 * The admin list's filter by where a car was posted through the panel (Facebook, Instagram, Viber, mobile.bg,
 * cars.bg): to see what is still to be posted. The SQL fragments are fixed here (never built from user input).
 */
public enum PostedFilter {
    /** Posted nowhere. */
    NONE("NOT (" + Sql.ANY + ")"),
    /** Posted somewhere. */
    ANY("(" + Sql.ANY + ")"),
    NOT_FACEBOOK("NOT " + Sql.FACEBOOK),
    NOT_INSTAGRAM("NOT " + Sql.INSTAGRAM),
    NOT_VIBER("NOT " + Sql.VIBER),
    NOT_MOBILE_BG("NOT " + Sql.MOBILE_BG),
    NOT_CARS_BG("NOT " + Sql.CARS_BG);

    private final String condition;

    PostedFilter(String condition) {
        this.condition = condition;
    }

    /** A condition on the car row aliased "c". */
    public String condition() {
        return condition;
    }

    /** The query value ("none", "any", "not_facebook", ...); unknown or empty values mean no filter (null). */
    public static PostedFilter fromParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (PostedFilter f : values()) {
            if (f.name().equalsIgnoreCase(value.trim())) {
                return f;
            }
        }
        return null;
    }

    private static final class Sql {
        static final String FACEBOOK = network("FACEBOOK");
        static final String INSTAGRAM = network("INSTAGRAM");
        static final String VIBER = network("VIBER");
        static final String MOBILE_BG = "EXISTS (SELECT 1 FROM mobile_bg_listing ml WHERE ml.car_id = c.id)";
        static final String CARS_BG = "EXISTS (SELECT 1 FROM cars_bg_listing cl WHERE cl.car_id = c.id)";
        static final String ANY = "EXISTS (SELECT 1 FROM social_post sp WHERE sp.car_id = c.id) OR " + MOBILE_BG
                + " OR " + CARS_BG;

        private static String network(String name) {
            return "EXISTS (SELECT 1 FROM social_post sp WHERE sp.car_id = c.id AND sp.network = '" + name + "')";
        }
    }
}
