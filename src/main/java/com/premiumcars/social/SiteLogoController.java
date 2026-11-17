package com.premiumcars.social;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** The website's logo: public for the site, uploaded and removed by the admins. */
@RestController
public class SiteLogoController {

    private final SiteLogoService service;

    public SiteLogoController(SiteLogoService service) {
        this.service = service;
    }

    @GetMapping("/api/public/logo")
    public SiteLogo publicLogo() {
        return service.logo();
    }

    @GetMapping("/api/admin/logo")
    public SiteLogo get() {
        return service.logo();
    }

    /** The image as the request body (Content-Type image/jpeg, image/png or image/webp); replaces the old one. */
    @PutMapping(value = "/api/admin/logo",
            consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp",
                    MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public SiteLogo set(@RequestBody byte[] image) {
        return service.setLogo(image);
    }

    @DeleteMapping("/api/admin/logo")
    public SiteLogo delete() {
        return service.deleteLogo();
    }

    /** How the logo is drawn; a null value clears that setting. */
    @PutMapping("/api/admin/logo/style")
    public SiteLogo setStyle(@Valid @RequestBody LogoStyle style) {
        return service.setStyle(style);
    }
}
