package com.premiumcars.social;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The logo's storage path: the {@code SITE_LOGO} row of {@code post_setting}. */
@Repository
public class SiteLogoRepository extends SiteImageRepository {

    static final String NAME = "SITE_LOGO";

    public SiteLogoRepository(JdbcClient jdbc) {
        super(jdbc, NAME);
    }
}
