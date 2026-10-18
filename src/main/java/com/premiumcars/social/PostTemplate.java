package com.premiumcars.social;

import java.time.Instant;

/**
 * The post text template of a network, as saved in the admin panel. The admin panel fills in the placeholders
 * ({@code {{price}}}, {@code {{year}}} ...) for a car and drops lines whose placeholders are empty; the backend
 * only stores the text.
 */
public record PostTemplate(SocialNetwork network, String body, Instant updatedAt) {
}
