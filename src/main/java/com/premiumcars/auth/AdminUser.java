package com.premiumcars.auth;

import java.time.Instant;

/** One row of {@code admin_user}. */
public record AdminUser(long id, String username, String passwordHash, int failedAttempts, Instant lockedUntil,
                        Instant lastLoginAt, Instant passwordChangedAt) {

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
