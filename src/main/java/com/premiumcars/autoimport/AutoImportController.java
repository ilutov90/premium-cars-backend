package com.premiumcars.autoimport;

import java.util.List;

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
import com.premiumcars.autoimport.AutoImportDtos.Overview;
import com.premiumcars.autoimport.AutoImportDtos.Preview;
import com.premiumcars.autoimport.AutoImportDtos.RunRequest;
import com.premiumcars.autoimport.AutoImportDtos.SourceUpdate;
import com.premiumcars.autoimport.AutoImportDtos.SwitchRequest;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.catalog.domain.CarSource;

/** "Автоматичен импорт" in the admin panel. */
@RestController
@RequestMapping("/api/admin/auto-import")
public class AutoImportController {

    private final AutoImportService service;

    public AutoImportController(AutoImportService service) {
        this.service = service;
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
