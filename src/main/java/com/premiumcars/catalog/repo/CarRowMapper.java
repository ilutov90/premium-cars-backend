package com.premiumcars.catalog.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.jdbc.core.RowMapper;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.pricing.PriceProfile;

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
                .euroStandard(rs.getObject("euro_standard", Integer.class))
                .bodyType(rs.getString("body_type"))
                .seats(rs.getObject("seats", Integer.class))
                .doors(rs.getObject("doors", Integer.class))
                .color(rs.getString("color"))
                .curbWeightKg(rs.getObject("curb_weight_kg", Integer.class))
                .dimensions(rs.getString("dimensions"))
                .description(rs.getString("description"))
                .extraSpecs(json.readStringMap(rs.getString("specs")))
                .features(json.readStringList(rs.getString("features")).stream()
                        .map(CarFeature::byName) // unknown names are dropped
                        .filter(Objects::nonNull)
                        .toList())
                .sheet(json.readSpecSheet(rs.getString("spec_sheet")))
                .build();
        var pricing = new PricingInputs(
                rs.getBigDecimal("source_price"),
                rs.getString("source_currency"),
                enumOf(PriceProfile.class, rs.getString("price_profile")),
                rs.getBigDecimal("profit_eur"),
                rs.getBigDecimal("fixed_total_eur"));
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
