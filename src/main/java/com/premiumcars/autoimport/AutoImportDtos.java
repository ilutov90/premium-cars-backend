package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.premiumcars.autoimport.AutoImportRunRepository.Run;
import com.premiumcars.autoimport.AutoImportTypes.BrandCount;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.common.PageResponse;

/** Requests and responses of {@code /api/admin/auto-import} (mirrored in the frontend's types.ts). */
public final class AutoImportDtos {

    private AutoImportDtos() {
    }

    /**
     * The whole tab.
     *
     * @param enabled  the main switch
     * @param running      the run going on now ("GUAZI", "AUTOWINI (dry run)") or null
     * @param maxMileageKm listings with more km are not imported (both sources); null = no limit
     */
    public record Overview(boolean enabled, String running, Integer maxMileageKm, List<SourceView> sources,
                           List<Run> runs) {
    }

    /** The mileage limit; null = no limit. */
    public record MaxMileageRequest(@Min(1000) @Max(2_000_000) Integer maxMileageKm) {
    }

    /**
     * One source's settings and queue.
     *
     * @param queue listings per state: QUEUED, IMPORTED, FAILED
     */
    public record SourceView(CarSource source, boolean enabled, SourceFilters filters, List<BrandView> brands,
                             Map<String, Integer> queue) {
    }

    /** @param excludedFuels the whole make's fuel types not imported (no model chosen) */
    public record BrandView(String code, String name, boolean enabled, int yearFrom, List<CatalogModel> models,
                            List<String> excludedFuels) {
    }

    /** A listing whose import failed {@code attempts} times in the last run (the next run tries it again). */
    public record Failure(CarSource source, String listingId, String url, String title, BigDecimal price,
                          String state, int attempts, String error, Instant updatedAt) {
    }

    /** The failures tab: a page of them and the reasons with their counts. */
    public record Failures(PageResponse<Failure> page, List<AutoImportListingRepository.Reason> reasons) {
    }

    /**
     * Deleting failures from the report: the chosen ones, or all that match the filter when {@code all}.
     */
    public record FailureDelete(@Size(max = 1000) List<@Valid FailureRef> items, boolean all, CarSource source,
                                @Size(max = 200) String text) {
    }

    public record FailureRef(@NotNull CarSource source, @NotBlank @Size(max = 60) String listingId) {
    }

    public record SwitchRequest(boolean enabled) {
    }

    /** Saving a source (or previewing it unsaved). */
    public record SourceUpdate(boolean enabled, @NotNull SourceFilters filters,
                               @NotNull @Size(max = 400) List<@Valid BrandUpdate> brands) {
    }

    public record BrandUpdate(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name,
                              boolean enabled, @Min(1990) @Max(2100) int yearFrom,
                              @Size(max = 300) List<@Valid ModelRef> models,
                              @Size(max = 20) List<@NotBlank @Size(max = 60) String> excludedFuels) {
    }

    /** @param excludedFuels the source's fuel types of this model not to import (autowini); empty = all */
    public record ModelRef(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name,
                           @Size(max = 20) List<@NotBlank @Size(max = 60) String> excludedFuels) {
    }

    /** A make of the source's own list (models loaded separately). */
    public record CatalogEntry(String code, String name) {
    }

    /**
     * The preview: how many listings match now, per make.
     *
     * @param error the site could not be read (e.g. from the server); null when it answered
     */
    public record Preview(CarSource source, List<BrandCount> brands, int total, String error) {
    }

    public record RunRequest(boolean dryRun) {
    }
}
