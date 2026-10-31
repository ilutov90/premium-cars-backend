package com.premiumcars.carsbg;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * The admin panel's cars.bg module. Publishing happens in the admin's own browser: the panel shows the form filled
 * in from the car, the browser extension types it into cars.bg, and the admin presses cars.bg's button. The server
 * never talks to cars.bg.
 */
@RestController
@RequestMapping("/api/admin")
public class CarsBgController {

    public record ListingRequest(@Size(max = 40) String offerId, @Size(max = 600) String url) {
    }

    private final CarsBgService service;

    public CarsBgController(CarsBgService service) {
        this.service = service;
    }

    @GetMapping("/cars/{id}/cars-bg")
    public CarsBgService.Form form(@PathVariable long id) {
        return service.form(id);
    }

    /** The extension reported the car published on cars.bg (relayed by the admin panel). */
    @PostMapping("/cars/{id}/cars-bg/listing")
    public CarsBgListing listing(@PathVariable long id, @Valid @RequestBody ListingRequest request,
                                 Principal principal) {
        return service.recordListing(id, request.offerId(), request.url(), principal.getName());
    }

    @DeleteMapping("/cars/{id}/cars-bg/listing")
    public ResponseEntity<Void> forgetListing(@PathVariable long id, Principal principal) {
        service.forgetListing(id, principal.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cars-bg/catalog")
    public CarsBgCatalog catalog() {
        return service.catalog();
    }

    @PutMapping("/cars-bg/catalog")
    public CarsBgCatalog saveCatalog(@Valid @RequestBody CarsBgCatalog catalog, Principal principal) {
        return service.saveCatalog(catalog, principal.getName());
    }
}
