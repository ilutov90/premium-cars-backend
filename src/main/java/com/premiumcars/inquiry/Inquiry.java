package com.premiumcars.inquiry;

import java.time.Instant;

/**
 * One row of the {@code inquiry} table, joined with the car it is about (if any).
 * {@code carId == null} means a general "find me a car" request.
 *
 * @param heardFrom   the customer's answer to "Откъде научихте за нас?"; null = not answered
 * @param attribution UTM parameters, landing page and referring site of the visit (never null)
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
        Instant createdAt,
        HeardFrom heardFrom,
        Attribution attribution
) {

    public enum Status { NEW, CONTACTED, CLOSED }
}
