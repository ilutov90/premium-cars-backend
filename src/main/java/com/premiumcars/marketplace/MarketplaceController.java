package com.premiumcars.marketplace;

import java.security.Principal;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The admin page "Съпоставяне на модели": our models against mobile.bg's and cars.bg's lists. */
@RestController
@RequestMapping("/api/admin/marketplace/models")
public class MarketplaceController {

    /** @param unavailable the site has no model for ours; else {@code targetModel} of the matched make */
    public record ChoiceRequest(@NotNull Site site, @NotBlank @Size(max = 100) String make,
                                @NotBlank @Size(max = 100) String model, @Size(max = 100) String targetModel,
                                boolean unavailable) {
    }

    private final MarketplaceService service;

    public MarketplaceController(MarketplaceService service) {
        this.service = service;
    }

    @GetMapping
    public MarketplaceService.Overview overview() {
        return service.overview();
    }

    @PutMapping
    public MarketplaceService.Overview choose(@Valid @RequestBody ChoiceRequest r, Principal principal) {
        service.choose(r.site(), r.make(), r.model(), r.targetModel(), r.unavailable(), principal.getName());
        return service.overview();
    }

    @DeleteMapping
    public MarketplaceService.Overview reset(@RequestParam Site site, @RequestParam String make,
                                             @RequestParam String model, Principal principal) {
        service.reset(site, make, model, principal.getName());
        return service.overview();
    }
}
