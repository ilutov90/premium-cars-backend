package com.premiumcars.social;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The business's contact details for the public site (footer): the same phone, Viber channel link and office as in
 * the post templates, and the Meta Pixel the site loads after the visitor's consent (null = none). Null fields = the
 * frontend's defaults.
 */
@RestController
public class PublicContactController {

    private final PostSettingsRepository settings;

    public PublicContactController(PostSettingsRepository settings) {
        this.settings = settings;
    }

    @GetMapping("/api/public/contact")
    public PostSettings contact() {
        return settings.find();
    }
}
