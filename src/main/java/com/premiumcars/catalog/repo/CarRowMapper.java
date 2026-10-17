package com.premiumcars.catalog.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.RowMapper;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.domain.Transmission;

/** ResultSet -> Car for queries that select {@link CarSearchSql#CAR_COLUMNS}. */
final class CarRowMapper implements RowMapper<Car> {

    private final JsonColumns json;

    CarRowMapper(JsonColumns json) {
        this.json = json;
    }

    @Override
    public Car mapRow(ResultSet rs, int rowNum) throws SQLException {
        CarSpecs specs = CarSpecs.builder()
                .title(rs.getString("title"))
                .make(rs.getString("make"))
                .model(rs.getString("model"))
                .trim(rs.getString("trim_level"))
                .year(rs.getObject("model_year", Integer.class))
                .firstRegistration(rs.getString("first_registration"))
                .mileageKm(rs.getObject("mileage_km", Integer.class))
                .fuelType(enumOf(FuelType.class, rs.getString("fuel_type")))
                .transmission(enumOf(Transmission.class, rs.getString("transmission")))
                .drivetrain(enumOf(Drivetrain.class, rs.getString("drivetrain")))
                .engineCc(rs.getObject("engine_cc", Integer.class))
                .engine(rs.getString("engine"))
                .powerHp(rs.getObject("power_hp", Integer.class))
                .batteryKwh(rs.getBigDecimal("battery_kwh"))
                .electricRangeKm(rs.getObject("electric_range_km", Integer.class))
                .rangeStandard(rs.getString("range_standard"))
                .motorPowerKw(rs.getObject("motor_power_kw", Integer.class))
                .bodyType(rs.getString("body_type"))
                .seats(rs.getObject("seats", Integer.class))
                .doors(rs.getObject("doors", Integer.class))
                .color(rs.getString("color"))
                .curbWeightKg(rs.getObject("curb_weight_kg", Integer.class))
                .dimensions(rs.getString("dimensions"))
                .description(rs.getString("description"))
                .extraSpecs(json.readStringMap(rs.getString("specs")))
                .build();
        var pricing = new PricingInputs(
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
                rs.getString("source_location"),
                specs,
                pricing,
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
