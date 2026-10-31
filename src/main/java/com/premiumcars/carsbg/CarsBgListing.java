package com.premiumcars.carsbg;

import java.time.Instant;

/**
 * A car's listing on cars.bg, as the browser extension reported it once the admin published it there. Kept to show
 * it in the admin panel and to stop the same car being posted twice; nothing is synced.
 *
 * @param offerId cars.bg's id of the offer (24 hex digits)
 * @param url     the public offer page, https://www.cars.bg/offer/{offerId}
 */
public record CarsBgListing(long carId, String offerId, String url, Instant publishedAt, String publishedBy) {
}
