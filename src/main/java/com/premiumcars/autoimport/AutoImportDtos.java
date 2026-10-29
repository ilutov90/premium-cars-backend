package com.premiumcars.autoimport;

import java.math.BigDecimal;
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
                           List<Run> runs, List<Failure> failures) {
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

    public record BrandView(String code, String name, boolean enabled, int yearFrom, List<CatalogModel> models) {
    }

    /** A listing whose import failed, for the report. */
    public record Failure(CarSource source, String listingId, String url, String title, BigDecimal price,
                          String state, int attempts, String error) {
    }

    public record SwitchRequest(boolean enabled) {
    }

    /** Saving a source (or previewing it unsaved). */
    public record SourceUpdate(boolean enabled, @NotNull SourceFilters filters,
                               @NotNull @Size(max = 400) List<@Valid BrandUpdate> brands) {
    }

    public record BrandUpdate(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name,
                              boolean enabled, @Min(1990) @Max(2100) int yearFrom,
                              @Size(max = 300) List<@Valid ModelRef> models) {
    }

    public record ModelRef(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name) {
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
