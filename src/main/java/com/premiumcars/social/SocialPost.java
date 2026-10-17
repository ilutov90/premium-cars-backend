package com.premiumcars.social;

import java.time.Instant;

/**
 * One row of {@code social_post}: a car published to a network from the admin panel.
 *
 * @param externalId the post id on Facebook / the media id on Instagram
 * @param permalink  link to the post, when the network returned one
 */
public record SocialPost(long id, long carId, SocialNetwork network, String externalId, String permalink,
                         int photos, Instant createdAt) {
}
