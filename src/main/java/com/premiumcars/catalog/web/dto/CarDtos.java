package com.premiumcars.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.pricing.PriceBreakdown;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** All request/response shapes of the catalog API in one place. */
public final class CarDtos {

    private CarDtos() {
    }

    // ---------------------------------------------------------------- public

    public record CarSummary(
            Long id,
            String slug,
            String title,
            String make,
            String model,
            Integer year,
            Integer mileageKm,
            FuelType fuelType,
            Transmission transmission,
            Origin origin,
            CarStatus status,
            BigDecimal totalPriceEur,
            String coverImageUrl
    ) {
    }

    /** Public price breakdown: our margin is folded into "service" so the purchase price is not exposed. */
    public record PublicPrice(
            BigDecimal carPriceEur,
            BigDecimal shippingEur,
            BigDecimal customsDutyEur,
            BigDecimal vatEur,
            BigDecimal serviceEur,
            BigDecimal totalEur
    ) {
        public static PublicPrice of(PriceBreakdown b) {
            if (b == null) {
                return null;
            }
            return new PublicPrice(b.carPriceEur(), b.shippingEur(), b.customsDutyEur(), b.vatEur(),
                    b.feesEur().add(b.marginEur()), b.totalEur());
        }
    }

    public record CarDetail(
            Long id,
            String slug,
            String title,
            String make,
            String model,
            String trim,
            Integer year,
            Integer mileageKm,
            FuelType fuelType,
            Transmission transmission,
            String bodyType,
            String engine,
            Integer powerHp,
            String color,
            String description,
            Map<String, String> specs,
            Origin origin,
            CarStatus status,
            List<String> imageUrls,
            PublicPrice price
    ) {
    }

    public record FilterOptions(List<String> makes, List<FuelType> fuelTypes, List<Transmission> transmissions,
                                List<Origin> origins) {
    }

    // ---------------------------------------------------------------- admin

    public record AdminImage(Long id, String url, String sourceUrl, int position) {
    }

    public record AdminCar(
            Long id,
            String slug,
            CarStatus status,
            CarSource source,
            String sourceUrl,
            String sourceRef,
            String title,
            String make,
            String model,
            String trim,
            Integer year,
            Integer mileageKm,
            FuelType fuelType,
            Transmission transmission,
            String bodyType,
            String engine,
            Integer powerHp,
            String color,
            String description,
            Map<String, String> specs,
            BigDecimal sourcePrice,
            String sourceCurrency,
            BigDecimal shippingEur,
            BigDecimal marginEur,
            BigDecimal extraDutyRate,
            PriceBreakdown price,
            List<AdminImage> images,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt
    ) {
    }

    public record CarUpdateRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 100) String make,
            @Size(max = 100) String model,
            @Size(max = 255) String trim,
            @Min(1950) @Max(2100) Integer year,
            @Min(0) @Max(2_000_000) Integer mileageKm,
            FuelType fuelType,
            Transmission transmission,
            @Size(max = 100) String bodyType,
            @Size(max = 100) String engine,
            @Min(0) @Max(3000) Integer powerHp,
            @Size(max = 50) String color,
            @Size(max = 20_000) String description,
            Map<String, String> specs,
            @DecimalMin("0") BigDecimal sourcePrice,
            @Size(min = 3, max = 3) String sourceCurrency,
            @DecimalMin("0") BigDecimal shippingEur,
            @DecimalMin("0") BigDecimal marginEur,
            @DecimalMin("0") @DecimalMax("2") BigDecimal extraDutyRate
    ) {
    }

    public record StatusChangeRequest(@NotNull CarStatus status) {
    }

    public record ImportRequest(@NotBlank @Size(max = 2000) String url) {
    }

    public record ImportResult(AdminCar car, boolean alreadyImported, int importedImages, List<String> warnings) {
    }
}
