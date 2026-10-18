package com.premiumcars.social;

import jakarta.validation.constraints.Size;

/**
 * Business details shared by all post templates; null = the admin panel's default.
 *
 * @param phone     {{phone}}, e.g. 0878131990
 * @param viberLink {{viber_link}}, the link to the Viber channel
 * @param office    {{office}}, e.g. София
 */
public record PostSettings(
        @Size(max = 50) String phone,
        @Size(max = 300) String viberLink,
        @Size(max = 100) String office
) {

    public PostSettings {
        phone = blankToNull(phone);
        viberLink = blankToNull(viberLink);
        office = blankToNull(office);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
