package com.premiumcars.social;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The share image's storage path: the {@code SITE_SHARE_IMAGE} row of {@code post_setting}. */
@Repository
public class SiteShareImageRepository extends SiteImageRepository {

    static final String NAME = "SITE_SHARE_IMAGE";

    public SiteShareImageRepository(JdbcClient jdbc) {
        super(jdbc, NAME);
    }
}
