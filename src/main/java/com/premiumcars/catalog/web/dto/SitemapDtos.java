package com.premiumcars.catalog.web.dto;

import java.time.Instant;
import java.util.List;

/** The public cars for the site's sitemap: nothing but the address, the change time and the cover photo. */
public final class SitemapDtos {

    private SitemapDtos() {
    }

    public record SitemapCar(String slug, Instant updatedAt, String imageUrl) {
    }

    public record SitemapPage(long total, List<SitemapCar> items) {
    }
}
