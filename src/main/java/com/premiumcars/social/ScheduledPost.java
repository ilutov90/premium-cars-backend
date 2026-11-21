package com.premiumcars.social;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code scheduled_post}: a post planned for {@code publishAt}.
 *
 * @param imageIds     the car's photos in posting order (empty = the first ones of the gallery)
 * @param error        why the last attempt failed (FAILED), or why it was skipped (CANCELLED: "Skipped: already posted to ...")
 * @param socialPostId the {@code social_post} row once PUBLISHED
 * @param carTitle     the car's title, for lists over all cars
 * @param linkOnly     a Viber post without photos: only the text (the car's link) is sent, or with {@code linkUrl}
 *                     that link (shown with the page's preview) and the text under it
 * @param linkUrl      the link of a "link + text" Viber post, sent before the text; null otherwise
 */
public record ScheduledPost(long id, long carId, String carTitle, SocialNetwork network, String body,
                            List<Long> imageIds, Instant publishAt, Status status, int attempts, String error,
                            Long socialPostId, String createdBy, Instant createdAt, Instant updatedAt, boolean linkOnly,
                            String linkUrl) {

    public enum Status {
        /** waiting for publishAt */
        SCHEDULED,
        /** being published right now */
        PUBLISHING,
        PUBLISHED,
        /** given up: see error; publish again by hand or schedule anew */
        FAILED,
        /** cancelled by an admin, or skipped because the car was posted to the network by hand meanwhile (see error) */
        CANCELLED
    }
}
