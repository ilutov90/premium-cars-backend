package com.premiumcars.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the {@code car} table.
 *
 * @param sourceUrl     original listing (admin only, never exposed publicly)
 * @param totalPriceEur final customer price, recalculated on every save and stored so the catalog can filter/sort by it
 */
public record Car(
        long id,
        String slug,
        CarStatus status,
        CarSource source,
        String sourceUrl,
        String sourceRef,
        CarDetails details,
        BigDecimal totalPriceEur,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt
) {

    /** Only PUBLISHED and RESERVED cars are visible on the public site. */
    public boolean isPubliclyVisible() {
        return status == CarStatus.PUBLISHED || status == CarStatus.RESERVED;
    }
}
