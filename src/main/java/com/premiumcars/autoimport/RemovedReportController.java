package com.premiumcars.autoimport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.autoimport.RemovedReportDtos.RemovedReport;
import com.premiumcars.autoimport.RemovedReportDtos.StatsReset;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.common.BadRequestException;

@RestController
public class RemovedReportController {

    private final RemovedReportRepository repository;
    private final AutoImportSettingsRepository settings;

    public RemovedReportController(RemovedReportRepository repository, AutoImportSettingsRepository settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /**
     * What disappears from the sources: the removed cars of the makes in our import filters, grouped; only those
     * removed since the last reset, never those deleted because they were out of the filters.
     */
    @GetMapping("/api/admin/reports/removed")
    public RemovedReport report(@RequestParam(required = false) Integer days,
                                @RequestParam(required = false) CarSource source,
                                @RequestParam(required = false) String groupBy,
                                @RequestParam(required = false) String make,
                                @RequestParam(required = false) String model) {
        if (days != null && days <= 0) {
            throw new BadRequestException("days must be a positive number");
        }
        return repository.report(days, source, RemovedGroupBy.parse(groupBy), make, model,
                settings.removedStatsFrom());
    }

    /** Resets the statistics: from now on only later removals count. Nothing is deleted. */
    @PostMapping("/api/admin/reports/removed/reset")
    public StatsReset reset() {
        return new StatsReset(settings.resetRemovedStats());
    }
}
