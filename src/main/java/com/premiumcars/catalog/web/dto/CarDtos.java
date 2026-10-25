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

    /**
     * @param priceEur final price with everything included; customers see only this number, never the
     *                 purchase price, costs or profit
     */
    public record CarDetail(
            Long id,
            String slug,
            CarStatus status,
            Origin origin,
            CarSpecs specs,
            List<String> imageUrls,
            BigDecimal priceEur
    ) {
    }

    public record FilterOptions(List<String> makes, List<FuelType> fuelTypes, List<Transmission> transmissions,
                                List<Origin> origins) {
    }

    // ---------------------------------------------------------------- admin

    /** A row of the admin car list: like {@link CarSummary} plus where the car was imported from. */
    public record AdminCarSummary(
            Long id,
            String slug,
            String title,
            Integer year,
            Integer mileageKm,
            CarStatus status,
            BigDecimal totalPriceEur,
            String coverImageUrl,
            CarSource source,
            String sourceRef,
            String sourceUrl
    ) {
    }

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
    /**
     * @param html optional: the listing page as the admin's browser saved it, for sites that refuse the server
     *             (guazi's bot check); the link still identifies the listing
     */
    public record ImportRequest(
            @NotBlank @Size(max = 2000) String url,
            @DecimalMin("0") BigDecimal price,
            @Size(min = 3, max = 3) String currency,
            @Size(max = 10_000_000) String html
    ) {
    }

    public record ImportResult(AdminCar car, boolean alreadyImported, int importedImages, List<String> warnings) {
    }
}
