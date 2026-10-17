package com.premiumcars.inquiry;

import java.time.Instant;

/**
 * One row of the {@code inquiry} table, joined with the car it is about (if any).
 * {@code carId == null} means a general "find me a car" request.
 */
public record Inquiry(
        long id,
        Long carId,
        String carTitle,
        String carSlug,
        String name,
        String phone,
        String email,
        String message,
        Status status,
        Instant createdAt
) {

    public enum Status { NEW, CONTACTED, CLOSED }
}
