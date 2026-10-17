package com.premiumcars.importing.extract;

import java.util.function.Predicate;

/**
 * Source-specific knowledge passed to the generic extractor.
 *
 * @param defaultCurrency currency assumed when the page does not state one
 * @param isCarPhoto      decides whether an image URL is a photo of the car (filters logos, icons, banners)
 */
public record ExtractionHints(String defaultCurrency, Predicate<String> isCarPhoto, int maxImages) {
}
