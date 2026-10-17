package com.premiumcars.catalog.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Everything that describes a car (public data). Pricing inputs live in {@link PricingInputs}.
 * <p>
 * Build instances with {@link #builder()} / {@link #toBuilder()}: the record has many fields of the same type,
 * and named setters keep them from being swapped. {@link Builder#build()} trims text, turns blanks into null and
 * cuts values to their column length.
 *
 * @param firstRegistration as shown by the source, normalized to "YYYY-MM" when possible
 * @param engineCc          displacement in cm³ (null for pure electric cars)
 * @param powerHp           total system power in metric hp
 * @param batteryKwh        traction battery capacity (electric, plug-in hybrid, range extender)
 * @param electricRangeKm   official electric range
 * @param rangeStandard     test cycle of the range: CLTC, NEDC, WLTP, EPA ...
 * @param motorPowerKw      total electric motor power
 * @param extraSpecs        any other label/value pairs found on the source page
 */
public record CarSpecs(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 100) String make,
        @Size(max = 100) String model,
        @Size(max = 255) String trim,
        @Min(1950) @Max(2100) Integer year,
        @Size(max = 20) String firstRegistration,
        @Min(0) @Max(2_000_000) Integer mileageKm,
        FuelType fuelType,
        Transmission transmission,
        Drivetrain drivetrain,
        @Min(0) @Max(10_000) Integer engineCc,
        @Size(max = 100) String engine,
        @Min(0) @Max(3000) Integer powerHp,
        @DecimalMin("0") @DecimalMax("500") BigDecimal batteryKwh,
        @Min(0) @Max(3000) Integer electricRangeKm,
        @Size(max = 10) String rangeStandard,
        @Min(0) @Max(3000) Integer motorPowerKw,
        @Size(max = 100) String bodyType,
        @Min(1) @Max(20) Integer seats,
        @Min(1) @Max(10) Integer doors,
        @Size(max = 50) String color,
        @Min(0) @Max(20_000) Integer curbWeightKg,
        @Size(max = 50) String dimensions,
        @Size(max = 20_000) String description,
        Map<String, String> extraSpecs
) {

    public CarSpecs {
        extraSpecs = extraSpecs == null ? Map.of() : extraSpecs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .title(title).make(make).model(model).trim(trim)
                .year(year).firstRegistration(firstRegistration).mileageKm(mileageKm)
                .fuelType(fuelType).transmission(transmission).drivetrain(drivetrain)
                .engineCc(engineCc).engine(engine).powerHp(powerHp)
                .batteryKwh(batteryKwh).electricRangeKm(electricRangeKm).rangeStandard(rangeStandard)
                .motorPowerKw(motorPowerKw)
                .bodyType(bodyType).seats(seats).doors(doors).color(color)
                .curbWeightKg(curbWeightKg).dimensions(dimensions)
                .description(description).extraSpecs(extraSpecs);
    }

    /** Fluent builder; every setter accepts null. */
    public static final class Builder {
        private String title;
        private String make;
        private String model;
        private String trim;
        private Integer year;
        private String firstRegistration;
        private Integer mileageKm;
        private FuelType fuelType;
        private Transmission transmission;
        private Drivetrain drivetrain;
        private Integer engineCc;
        private String engine;
        private Integer powerHp;
        private BigDecimal batteryKwh;
        private Integer electricRangeKm;
        private String rangeStandard;
        private Integer motorPowerKw;
        private String bodyType;
        private Integer seats;
        private Integer doors;
        private String color;
        private Integer curbWeightKg;
        private String dimensions;
        private String description;
        private Map<String, String> extraSpecs = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder title(String v) { title = v; return this; }
        public Builder make(String v) { make = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder trim(String v) { trim = v; return this; }
        public Builder year(Integer v) { year = v; return this; }
        public Builder firstRegistration(String v) { firstRegistration = v; return this; }
        public Builder mileageKm(Integer v) { mileageKm = v; return this; }
        public Builder fuelType(FuelType v) { fuelType = v; return this; }
        public Builder transmission(Transmission v) { transmission = v; return this; }
        public Builder drivetrain(Drivetrain v) { drivetrain = v; return this; }
        public Builder engineCc(Integer v) { engineCc = v; return this; }
        public Builder engine(String v) { engine = v; return this; }
        public Builder powerHp(Integer v) { powerHp = v; return this; }
        public Builder batteryKwh(BigDecimal v) { batteryKwh = v; return this; }
        public Builder electricRangeKm(Integer v) { electricRangeKm = v; return this; }
        public Builder rangeStandard(String v) { rangeStandard = v; return this; }
        public Builder motorPowerKw(Integer v) { motorPowerKw = v; return this; }
        public Builder bodyType(String v) { bodyType = v; return this; }
        public Builder seats(Integer v) { seats = v; return this; }
        public Builder doors(Integer v) { doors = v; return this; }
        public Builder color(String v) { color = v; return this; }
        public Builder curbWeightKg(Integer v) { curbWeightKg = v; return this; }
        public Builder dimensions(String v) { dimensions = v; return this; }
        public Builder description(String v) { description = v; return this; }

        public Builder extraSpecs(Map<String, String> v) {
            extraSpecs = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }

        public String title() { return title; }
        public String make() { return make; }
        public String model() { return model; }
        public Integer year() { return year; }
        public FuelType fuelType() { return fuelType; }

        /** Trims text, maps blanks to null, cuts to column sizes. The title falls back to "Car". */
        public CarSpecs build() {
            String t = clean(title, 255);
            return new CarSpecs(
                    t == null ? "Car" : t,
                    clean(make, 100),
                    clean(model, 100),
                    clean(trim, 255),
                    year,
                    clean(firstRegistration, 20),
                    mileageKm,
                    fuelType,
                    transmission,
                    drivetrain,
                    engineCc,
                    clean(engine, 100),
                    powerHp,
                    batteryKwh,
                    electricRangeKm,
                    clean(rangeStandard, 10),
                    motorPowerKw,
                    clean(bodyType, 100),
                    seats,
                    doors,
                    clean(color, 50),
                    curbWeightKg,
                    clean(dimensions, 50),
                    clean(description, 20_000),
                    cleanSpecs(extraSpecs));
        }

        /** Keeps insertion order (it is the display order) and drops empty labels or values. */
        private static Map<String, String> cleanSpecs(Map<String, String> specs) {
            Map<String, String> out = new LinkedHashMap<>();
            specs.forEach((k, v) -> {
                String key = clean(k, 100);
                String value = clean(v, 500);
                if (key != null && value != null) {
                    out.put(key, value);
                }
            });
            return Collections.unmodifiableMap(out);
        }

        private static String clean(String s, int max) {
            if (s == null || s.isBlank()) {
                return null;
            }
            String v = s.trim();
            return v.length() > max ? v.substring(0, max) : v;
        }
    }
}
