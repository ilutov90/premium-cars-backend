package com.premiumcars.catalog.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.RowMapper;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarDetails;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;

/** ResultSet -> Car for queries that select {@link CarSearchSql#CAR_COLUMNS}. */
final class CarRowMapper implements RowMapper<Car> {

    private final JsonColumns json;

    CarRowMapper(JsonColumns json) {
        this.json = json;
    }

    @Override
    public Car mapRow(ResultSet rs, int rowNum) throws SQLException {
        var details = new CarDetails(
                rs.getString("title"),
                rs.getString("make"),
                rs.getString("model"),
                rs.getString("trim_level"),
                rs.getObject("model_year", Integer.class),
                rs.getObject("mileage_km", Integer.class),
                enumOf(FuelType.class, rs.getString("fuel_type")),
                enumOf(Transmission.class, rs.getString("transmission")),
                rs.getString("body_type"),
                rs.getString("engine"),
                rs.getObject("power_hp", Integer.class),
                rs.getString("color"),
                rs.getString("description"),
                json.readStringMap(rs.getString("specs")),
                rs.getBigDecimal("source_price"),
                rs.getString("source_currency"),
                rs.getBigDecimal("shipping_eur"),
                rs.getBigDecimal("margin_eur"),
                rs.getBigDecimal("extra_duty_rate"));
        return new Car(
                rs.getLong("id"),
                rs.getString("slug"),
                CarStatus.valueOf(rs.getString("status")),
                CarSource.valueOf(rs.getString("source")),
                rs.getString("source_url"),
                rs.getString("source_ref"),
                details,
                rs.getBigDecimal("total_price_eur"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "published_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
