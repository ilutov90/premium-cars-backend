package com.premiumcars.social;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code scheduled_post}: a post planned for {@code publishAt}.
 *
 * @param imageIds     the car's photos in posting order (empty = the first ones of the gallery)
 * @param error        why the last attempt failed (FAILED, or SCHEDULED again for a retry)
 * @param socialPostId the {@code social_post} row once PUBLISHED
 * @param carTitle     the car's title, for lists over all cars
 */
public record ScheduledPost(long id, long carId, String carTitle, SocialNetwork network, String body,
                            List<Long> imageIds, Instant publishAt, Status status, int attempts, String error,
                            Long socialPostId, String createdBy, Instant createdAt, Instant updatedAt) {

    public enum Status {
        /** waiting for publishAt */
        SCHEDULED,
        /** being published right now */
        PUBLISHING,
        PUBLISHED,
        /** given up: see error; publish again by hand or schedule anew */
        FAILED,
        CANCELLED
    }
}
