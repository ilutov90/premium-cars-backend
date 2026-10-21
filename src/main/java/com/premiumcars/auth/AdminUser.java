package com.premiumcars.auth;

import java.time.Instant;

/**
 * One row of {@code admin_user}. All admins have the same rights.
 *
 * @param mustChangePassword the password was set by another admin (new user or reset): until the user sets an own
 *                           one, only the password change, /me and logout are allowed
 */
public record AdminUser(long id, String username, String passwordHash, int failedAttempts, Instant lockedUntil,
                        Instant lastLoginAt, Instant passwordChangedAt, boolean mustChangePassword,
                        Instant createdAt) {

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
