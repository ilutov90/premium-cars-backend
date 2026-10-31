package com.premiumcars.marketplace;

import java.time.Instant;

/**
 * An admin's choice for one of our makes + models on a listing site (the mapping page): the site's make and model,
 * or that the site has none ({@code unavailable}). Keyed by our make and model ({@link ModelNames#key}).
 */
public record ModelMapping(Site site, String make, String model, String targetBrand, String targetModel,
                           boolean unavailable, String setBy, Instant setAt) {
}
