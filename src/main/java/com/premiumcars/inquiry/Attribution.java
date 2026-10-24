package com.premiumcars.inquiry;

/**
 * How the customer reached the site, as their browser kept it from the first page of the visit: the UTM parameters
 * of the link (e.g. utm_source=facebook from a post), the page they landed on and the referring site's host.
 * Every field is optional; blank values become null and long ones are cut to the column sizes.
 */
public record Attribution(
        String utmSource,
        String utmMedium,
        String utmCampaign,
        String utmContent,
        String landingPath,
        String referrer
) {

    public static final Attribution NONE = new Attribution(null, null, null, null, null, null);

    public Attribution {
        utmSource = clean(utmSource, 100);
        utmMedium = clean(utmMedium, 100);
        utmCampaign = clean(utmCampaign, 100);
        utmContent = clean(utmContent, 100);
        landingPath = clean(landingPath, 300);
        referrer = clean(referrer, 200);
    }

    public boolean isEmpty() {
        return equals(NONE);
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
