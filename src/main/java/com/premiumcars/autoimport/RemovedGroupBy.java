package com.premiumcars.autoimport;

import com.premiumcars.common.BadRequestException;

/** How the removed-cars statistics are grouped; each value owns its fixed SQL column lists (never user input). */
public enum RemovedGroupBy {
    MAKE("make", "r.make, NULL::varchar AS model, NULL::integer AS year", "r.make"),
    MODEL("model", "r.make, r.model, NULL::integer AS year", "r.make, r.model"),
    YEAR("year", "NULL::varchar AS make, NULL::varchar AS model, r.year", "r.year"),
    MAKE_YEAR("make_year", "r.make, NULL::varchar AS model, r.year", "r.make, r.year"),
    MODEL_YEAR("model_year", "r.make, r.model, r.year", "r.make, r.model, r.year");

    private final String param;
    private final String selectSql;
    private final String groupSql;

    RemovedGroupBy(String param, String selectSql, String groupSql) {
        this.param = param;
        this.selectSql = selectSql;
        this.groupSql = groupSql;
    }

    public String selectSql() {
        return selectSql;
    }

    public String groupSql() {
        return groupSql;
    }

    public String orderSql() {
        return groupSql.replace(", ", " NULLS LAST, ") + " NULLS LAST";
    }

    public static RemovedGroupBy parse(String value) {
        if (value == null || value.isBlank()) {
            return MAKE;
        }
        for (RemovedGroupBy g : values()) {
            if (g.param.equalsIgnoreCase(value.trim())) {
                return g;
            }
        }
        throw new BadRequestException("groupBy must be one of: make, model, year, make_year, model_year");
    }
}
