package com.premiumcars.social;

import java.time.Instant;

/**
 * A text template (a network's post, or mobile.bg's description), as saved in the admin panel. The admin panel fills in the placeholders
 * ({@code {{price}}}, {@code {{year}}} ...) for a car and drops lines whose placeholders are empty; the backend
 * only stores the text.
 */
public record PostTemplate(TemplateKey network, String body, Instant updatedAt) {
}
