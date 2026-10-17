package com.premiumcars.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.pricing.PriceBreakdown;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * All request/response shapes of the catalog API in one place.
 * Car data is nested as {@code specs} (public description) and {@code pricing} (admin-only inputs).
 */
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
            Integer powerHp,
            BigDecimal batteryKwh,
            Integer electricRangeKm,
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
            CarStatus status,
            Origin origin,
            CarSpecs specs,
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
            String sourceLocation,
            CarSpecs specs,
            PricingInputs pricing,
            PriceBreakdown price,
            List<AdminImage> images,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt
    ) {
    }

    public record CarUpdateRequest(@NotNull @Valid CarSpecs specs, @NotNull @Valid PricingInputs pricing) {
    }

    public record StatusChangeRequest(@NotNull CarStatus status) {
    }

    /**
     * @param price    optional: the price to use instead of the one on the page (autowini shows prices
     *                 only to some members, so it is usually entered here)
     * @param currency currency of {@code price}; defaults to EUR
     */
    public record ImportRequest(
            @NotBlank @Size(max = 2000) String url,
            @DecimalMin("0") BigDecimal price,
            @Size(min = 3, max = 3) String currency
    ) {
    }

    public record ImportResult(AdminCar car, boolean alreadyImported, int importedImages, List<String> warnings) {
    }
}
