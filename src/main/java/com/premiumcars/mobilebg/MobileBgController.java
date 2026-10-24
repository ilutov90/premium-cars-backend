package com.premiumcars.mobilebg;

import java.security.Principal;

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
 * The admin panel's mobile.bg module. Publishing itself happens in the admin's own browser: the panel shows the
 * form filled in from the car, the admin edits it, and the browser extension types it into mobile.bg, where the
 * admin presses the buttons. The server never talks to mobile.bg.
 */
@RestController
@RequestMapping("/api/admin")
public class MobileBgController {

    public record ListingRequest(@Size(max = 40) String listingId, @Size(max = 600) String url) {
    }

    private final MobileBgService service;

    public MobileBgController(MobileBgService service) {
        this.service = service;
    }

    @GetMapping("/cars/{id}/mobile-bg")
    public MobileBgService.Form form(@PathVariable long id) {
        return service.form(id);
    }

    /** The extension reported the car published on mobile.bg (relayed by the admin panel). */
    @PostMapping("/cars/{id}/mobile-bg/listing")
    public MobileBgListing listing(@PathVariable long id, @Valid @RequestBody ListingRequest request,
                                   Principal principal) {
        return service.recordListing(id, request.listingId(), request.url(), principal.getName());
    }

    @GetMapping("/mobile-bg/catalog")
    public MobileBgCatalog catalog() {
        return service.catalog();
    }

    @PutMapping("/mobile-bg/catalog")
    public MobileBgCatalog saveCatalog(@Valid @RequestBody MobileBgCatalog catalog, Principal principal) {
        return service.saveCatalog(catalog, principal.getName());
    }
}
