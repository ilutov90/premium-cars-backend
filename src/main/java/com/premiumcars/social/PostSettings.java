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
 * @param googleAdsId       the Google Ads tag, e.g. AW-1234567890; null = none (public by nature, like the pixel)
 * @param googleAnalyticsId the Google Analytics 4 measurement id, e.g. G-XXXXXXXXXX; null = none
 * @param googleLeadLabel   Google Ads conversion label of a sent inquiry; null = none
 * @param googlePhoneLabel  Google Ads conversion label of a click on the phone; null = none
 * @param googleViberLabel  Google Ads conversion label of a click on Viber; null = none
 * @param locationKr        the location line of a car from Korea in the post templates; null = the frontend's default
 * @param locationCn        the same for a car from China
 * @param locationCa        the same for a car from Canada
 */
public record PostSettings(
        @Size(max = 50) String phone,
        @Size(max = 300) String viberLink,
        @Size(max = 100) String office,
        @Pattern(regexp = "\\d{5,20}", message = "must be the pixel's number") String metaPixelId,
        @Pattern(regexp = "AW-\\d{6,15}", message = "must look like AW-1234567890") String googleAdsId,
        @Pattern(regexp = "G-[A-Z0-9]{6,15}", message = "must look like G-XXXXXXXXXX") String googleAnalyticsId,
        @Pattern(regexp = LABEL, message = "must be the conversion label from Google Ads") String googleLeadLabel,
        @Pattern(regexp = LABEL, message = "must be the conversion label from Google Ads") String googlePhoneLabel,
        @Pattern(regexp = LABEL, message = "must be the conversion label from Google Ads") String googleViberLabel,
        @Size(max = 300) String locationKr,
        @Size(max = 300) String locationCn,
        @Size(max = 300) String locationCa
) {

    private static final String LABEL = "[A-Za-z0-9_-]{5,60}";

    public PostSettings(String phone, String viberLink, String office, String metaPixelId) {
        this(phone, viberLink, office, metaPixelId, null, null, null, null, null, null, null, null);
    }

    public PostSettings(String phone, String viberLink, String office, String metaPixelId, String googleAdsId,
                        String googleAnalyticsId, String googleLeadLabel, String googlePhoneLabel,
                        String googleViberLabel) {
        this(phone, viberLink, office, metaPixelId, googleAdsId, googleAnalyticsId, googleLeadLabel, googlePhoneLabel,
                googleViberLabel, null, null, null);
    }

    public PostSettings {
        phone = blankToNull(phone);
        viberLink = blankToNull(viberLink);
        office = blankToNull(office);
        metaPixelId = blankToNull(metaPixelId);
        googleAdsId = blankToNull(googleAdsId);
        googleAnalyticsId = blankToNull(googleAnalyticsId);
        googleLeadLabel = blankToNull(googleLeadLabel);
        googlePhoneLabel = blankToNull(googlePhoneLabel);
        googleViberLabel = blankToNull(googleViberLabel);
        locationKr = blankToNull(locationKr);
        locationCn = blankToNull(locationCn);
        locationCa = blankToNull(locationCa);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
