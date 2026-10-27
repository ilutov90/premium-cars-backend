package com.premiumcars.catalog.domain;

/**
 * Catalog sort orders. The SQL fragments are fixed here (never built from user input),
 * so the {@code sort} query parameter cannot inject SQL.
 */
public enum CarSort {
    NEWEST("c.published_at DESC NULLS LAST, c.id DESC"),
    PRICE_ASC("c.total_price_eur ASC NULLS LAST, c.id DESC"),
    PRICE_DESC("c.total_price_eur DESC NULLS LAST, c.id DESC"),
    YEAR_DESC("c.model_year DESC NULLS LAST, c.id DESC"),
    MILEAGE_ASC("c.mileage_km ASC NULLS LAST, c.id DESC"),
    /** Admin list: most recently edited first. */
    UPDATED("c.updated_at DESC, c.id DESC"),
    /** Admin list: most recently imported or added first. */
    CREATED("c.created_at DESC, c.id DESC");

    private final String orderBy;

    CarSort(String orderBy) {
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }

    /** The admin list's sort ("updated", "created", "price_desc", "price_asc"); unknown or empty values mean UPDATED. */
    public static CarSort fromAdminParam(String value) {
        if (value == null) {
            return UPDATED;
        }
        return switch (value) {
            case "created" -> CREATED;
            case "price_desc" -> PRICE_DESC;
            case "price_asc" -> PRICE_ASC;
            default -> UPDATED;
        };
    }

    /** Maps the public query value ("price_asc", ...) to a sort; unknown or empty values mean NEWEST. */
    public static CarSort fromParam(String value) {
        if (value == null) {
            return NEWEST;
        }
        return switch (value) {
            case "price_asc" -> PRICE_ASC;
            case "price_desc" -> PRICE_DESC;
            case "year_desc" -> YEAR_DESC;
            case "mileage_asc" -> MILEAGE_ASC;
            default -> NEWEST;
        };
    }
}
