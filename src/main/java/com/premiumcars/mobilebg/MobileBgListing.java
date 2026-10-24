package com.premiumcars.mobilebg;

import java.time.Instant;

/**
 * A car's listing on mobile.bg, as the browser extension reported it once the admin published it there. Kept only
 * to show it in the admin panel (link, date) and to warn before posting the same car twice; nothing is synced.
 *
 * @param listingId mobile.bg's number of the listing (may be null when only the link was seen)
 * @param url       the public listing page (may be null when only the number was seen)
 */
public record MobileBgListing(long carId, String listingId, String url, Instant publishedAt, String publishedBy) {
}
