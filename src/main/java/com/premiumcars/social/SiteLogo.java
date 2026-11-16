package com.premiumcars.social;

/**
 * The site's logo as the admin panel and the header see it: the public URL of the uploaded file and how it is drawn
 * (header height in px, corner radius in px, edge feather in percent). Each is {@code null} while none is set.
 */
public record SiteLogo(String url, Integer height, Integer radius, Integer feather) {

    public SiteLogo(String url, LogoStyle style) {
        this(url, style.height(), style.radius(), style.feather());
    }
}
