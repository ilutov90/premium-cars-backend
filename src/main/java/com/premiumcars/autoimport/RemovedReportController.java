package com.premiumcars.autoimport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.autoimport.RemovedReportDtos.RemovedReport;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.common.BadRequestException;

@RestController
public class RemovedReportController {

    private final RemovedReportRepository repository;

    public RemovedReportController(RemovedReportRepository repository) {
        this.repository = repository;
    }

    /** What disappears from the sources: the removed cars of the makes in our import filters, grouped. */
    @GetMapping("/api/admin/reports/removed")
    public RemovedReport report(@RequestParam(required = false) Integer days,
                                @RequestParam(required = false) CarSource source,
                                @RequestParam(required = false) String groupBy,
                                @RequestParam(required = false) String make,
                                @RequestParam(required = false) String model) {
        if (days != null && days <= 0) {
            throw new BadRequestException("days must be a positive number");
        }
        return repository.report(days, source, RemovedGroupBy.parse(groupBy), make, model);
    }
}
