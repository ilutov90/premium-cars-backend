package com.premiumcars.social;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Business details shared by all post templates, and the site's Meta Pixel; null = the admin panel's default.
 *
 * @param phone       {{phone}}, e.g. 0878131990
 * @param viberLink   {{viber_link}}, the link to the Viber channel
 * @param office      {{office}}, e.g. София
 * @param metaPixelId the Meta (Facebook) Pixel the public site loads after the visitor accepts marketing cookies;
 *                    null = no pixel. Public by nature (it is in every page that loads it), so not a secret.
 */
public record PostSettings(
        @Size(max = 50) String phone,
        @Size(max = 300) String viberLink,
        @Size(max = 100) String office,
        @Pattern(regexp = "\\d{5,20}", message = "must be the pixel's number") String metaPixelId
) {

    public PostSettings {
        phone = blankToNull(phone);
        viberLink = blankToNull(viberLink);
        office = blankToNull(office);
        metaPixelId = blankToNull(metaPixelId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
