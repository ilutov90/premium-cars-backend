package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.autoimport.RemovedReportDtos.RemovedReport;
import com.premiumcars.autoimport.RemovedReportDtos.Row;
import com.premiumcars.autoimport.RemovedReportDtos.Week;
import com.premiumcars.catalog.domain.CarSource;

/** SQL for the statistics of {@code auto_import_removed}: which cars disappear from the sources. */
@Repository
public class RemovedReportRepository {

    private static final String PERIOD = " AND r.removed_at >= now() - make_interval(days => :days)";
    private static final String SOURCE = " AND r.source = :source";
    private static final String COUNTED = " AND r.make IS NOT NULL AND EXISTS (SELECT 1 FROM auto_import_brand b"
            + " WHERE b.source = r.source AND lower(b.name) = lower(r.make))";
    private static final String MAKE = " AND lower(r.make) = lower(:make)";
    private static final String MODEL = " AND r.model = :model";

    private record Where(String sql, Map<String, Object> params) {
    }

    private final JdbcClient jdbc;

    public RemovedReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RemovedReport report(Integer days, CarSource source, RemovedGroupBy groupBy, String make, String model) {
        String mk = blankToNull(make);
        String md = blankToNull(model);
        Where period = where(days, source, null, null, false);
        Where filtered = where(days, source, mk, md, true);
        Where counted = where(days, source, null, null, true);
        Where unnamed = where(days, source, null, null, false);

        int total = jdbc.sql("SELECT count(*) FROM auto_import_removed r WHERE true" + filtered.sql())
                .params(filtered.params()).query(Integer.class).single();
        int unrecognized = jdbc.sql("SELECT count(*) FROM auto_import_removed r WHERE r.make IS NULL"
                        + unnamed.sql())
                .params(unnamed.params()).query(Integer.class).single();
        var firstRemovedAt = jdbc.sql("SELECT min(removed_at) FROM auto_import_removed")
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).single();

        List<Row> rows = jdbc.sql("SELECT " + groupBy.selectSql() + ", count(*) AS cnt, "
                        + "round(avg(r.price_eur)) AS avg_price, "
                        + "round(avg(extract(epoch FROM r.removed_at - r.first_seen) / 86400.0) "
                        + "FILTER (WHERE r.first_seen IS NOT NULL), 1) AS avg_days "
                        + "FROM auto_import_removed r WHERE true" + filtered.sql()
                        + " GROUP BY " + groupBy.groupSql()
                        + " ORDER BY cnt DESC, " + groupBy.orderSql())
                .params(filtered.params())
                .query((rs, n) -> {
                    int count = rs.getInt("cnt");
                    BigDecimal price = rs.getBigDecimal("avg_price");
                    BigDecimal days2 = rs.getBigDecimal("avg_days");
                    return new Row(rs.getString("make"), rs.getString("model"), rs.getObject("year", Integer.class),
                            count, share(count, total), price == null ? null : price.longValue(),
                            days2 == null ? null : days2.doubleValue());
                })
                .list();

        List<Week> weeks = jdbc.sql("SELECT CAST(date_trunc('week', r.removed_at AT TIME ZONE 'Europe/Sofia') AS date)"
                        + " AS week_start, count(*) AS cnt FROM auto_import_removed r WHERE true" + filtered.sql()
                        + " GROUP BY week_start ORDER BY week_start")
                .params(filtered.params())
                .query((rs, n) -> new Week(rs.getString("week_start"), rs.getInt("cnt")))
                .list();

        List<String> makes = jdbc.sql("SELECT DISTINCT r.make FROM auto_import_removed r WHERE true"
                        + counted.sql() + " ORDER BY r.make")
                .params(counted.params()).query(String.class).list();

        List<String> models = List.of();
        if (mk != null) {
            Where forMake = where(days, source, mk, null, true);
            models = jdbc.sql("SELECT DISTINCT r.model FROM auto_import_removed r WHERE r.model IS NOT NULL"
                            + forMake.sql() + " ORDER BY r.model")
                    .params(forMake.params()).query(String.class).list();
        }
        return new RemovedReport(total, unrecognized, firstRemovedAt == null ? null : firstRemovedAt.toInstant(),
                rows, weeks, makes, models);
    }

    private static Where where(Integer days, CarSource source, String make, String model, boolean ourMakesOnly) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        if (days != null) {
            sql.append(PERIOD);
            params.put("days", days);
        }
        if (source != null) {
            sql.append(SOURCE);
            params.put("source", source.name());
        }
        if (ourMakesOnly) {
            sql.append(COUNTED);
        }
        if (make != null) {
            sql.append(MAKE);
            params.put("make", make);
        }
        if (model != null) {
            sql.append(MODEL);
            params.put("model", model);
        }
        return new Where(sql.toString(), params);
    }

    private static double share(int count, int total) {
        if (total == 0) {
            return 0;
        }
        return BigDecimal.valueOf(count * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
