package com.premiumcars.autoimport;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.autoimport.AutoImportDtos.CatalogEntry;
import com.premiumcars.autoimport.AutoImportDtos.FailureDelete;
import com.premiumcars.autoimport.AutoImportDtos.Failures;
import com.premiumcars.autoimport.AutoImportDtos.MaxMileageRequest;
import com.premiumcars.autoimport.AutoImportDtos.Overview;
import com.premiumcars.autoimport.AutoImportDtos.Preview;
import com.premiumcars.autoimport.AutoImportDtos.RunRequest;
import com.premiumcars.autoimport.AutoImportDtos.SourceUpdate;
import com.premiumcars.autoimport.AutoImportDtos.SwitchRequest;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FuelOption;
import com.premiumcars.catalog.domain.CarSource;

/** "Автоматичен импорт" in the admin panel. */
@RestController
@RequestMapping("/api/admin/auto-import")
public class AutoImportController {

    private final AutoImportService service;
    private final AutoImportExport exporter;

    public AutoImportController(AutoImportService service, AutoImportExport exporter) {
        this.service = service;
        this.exporter = exporter;
    }

    @GetMapping
    public Overview overview() {
        return service.overview();
    }

    /** The main switch. */
    @PutMapping("/enabled")
    public Overview setEnabled(@RequestBody SwitchRequest request) {
        return service.setEnabled(request.enabled());
    }

    /** The mileage limit of both sources. */
    @PutMapping("/max-mileage")
    public Overview setMaxMileage(@Valid @RequestBody MaxMileageRequest request) {
        return service.setMaxMileage(request.maxMileageKm());
    }

    /** The highest FOB price of both sources. */
    @PutMapping("/max-price")
    public Overview setMaxPrice(@Valid @RequestBody AutoImportDtos.MaxPriceRequest request) {
        return service.setMaxPrice(request.maxPriceEur());
    }

    /** All failed listings, a page at a time: by site, by text in the error, title or listing id. */
    @GetMapping("/failures")
    public Failures failures(@RequestParam(required = false) CarSource source,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return service.failures(source, q, page, size);
    }

    /** The cars a run hid, with where each one was posted (to take those posts down). */
    @GetMapping("/runs/{id}/removed")
    public List<AutoImportRunRepository.Removed> removed(@PathVariable long id) {
        return service.removed(id);
    }

    /** Deletes failures from the report (chosen ones or all matching the filter). */
    @PostMapping("/failures/delete")
    public Map<String, Integer> deleteFailures(@Valid @RequestBody FailureDelete request) {
        return Map.of("deleted", service.deleteFailures(request));
    }

    @PutMapping("/{source}")
    public Overview save(@PathVariable CarSource source, @Valid @RequestBody SourceUpdate update) {
        return service.save(source, update);
    }

    @GetMapping("/{source}/catalog")
    public List<CatalogEntry> catalog(@PathVariable CarSource source) {
        return service.catalog(source);
    }

    @PostMapping("/{source}/catalog/refresh")
    public List<CatalogEntry> refreshCatalog(@PathVariable CarSource source) {
        service.refreshCatalog(source);
        return service.catalog(source);
    }

    @GetMapping("/{source}/catalog/{brandCode}/models")
    public List<CatalogModel> models(@PathVariable CarSource source, @PathVariable String brandCode,
                                     @RequestParam(defaultValue = "false") boolean refresh) {
        return service.models(source, brandCode, refresh);
    }

    /** A make's (or one model's) fuel types with their counts, for the "Двигател" column. */
    @GetMapping("/{source}/catalog/{brandCode}/fuels")
    public List<FuelOption> fuels(@PathVariable CarSource source, @PathVariable String brandCode,
                                  @RequestParam(required = false) String model,
                                  @RequestParam(defaultValue = "1990") int yearFrom) {
        return service.fuels(source, brandCode, model, yearFrom);
    }

    /** How many cars of the source are on the site or drafts. */
    @GetMapping("/{source}/check")
    public AutoImportListingRepository.CatalogCheck check(@PathVariable CarSource source) {
        return service.check(source);
    }

    /**
     * A ZIP with every listing row of the source and its last runs (CSV), to check what the runs queue and why.
     */
    @GetMapping("/{source}/export")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            export(@PathVariable CarSource source) {
        String name = "auto-import-" + source.name().toLowerCase(java.util.Locale.ROOT) + "-"
                + java.time.LocalDate.now(java.time.ZoneId.of("Europe/Sofia")) + ".zip";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + name + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
                .body(out -> exporter.write(source, out));
    }

    /** Cars that are one listing more than once, and which one would stay. */
    @GetMapping("/{source}/duplicates")
    public AutoImportService.Duplicates duplicates(@PathVariable CarSource source) {
        return service.duplicates(source);
    }

    /** Empties the source's queue (the next run queues again what is still at the source). */
    @PostMapping("/{source}/queue/clear")
    public Overview clearQueue(@PathVariable CarSource source) {
        return service.clearQueue(source);
    }

    /** Deletes them, keeping one car per listing. */
    @PostMapping("/{source}/duplicates/delete")
    public AutoImportService.Duplicates deleteDuplicates(@PathVariable CarSource source) {
        return service.deleteDuplicates(source);
    }

    @PostMapping("/{source}/preview")
    public Preview preview(@PathVariable CarSource source, @Valid @RequestBody SourceUpdate update) {
        return service.preview(source, update);
    }

    @PostMapping("/{source}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Overview run(@PathVariable CarSource source, @RequestBody RunRequest request) {
        return service.run(source, request.dryRun());
    }
}
