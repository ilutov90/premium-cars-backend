package com.premiumcars.marketplace;

import java.time.Instant;
import java.util.List;

/**
 * A listing site's makes and models as the browser extension last read them; ids where the site has them (cars.bg),
 * null where it works with names (mobile.bg).
 */
public record SiteCatalog(Site site, List<SiteBrand> brands, Instant capturedAt) {

    public SiteCatalog {
        brands = brands == null ? List.of() : List.copyOf(brands);
    }

    public boolean isEmpty() {
        return brands.isEmpty();
    }

    public record SiteBrand(String id, String name, List<SiteModel> models) {
        public SiteBrand {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    public record SiteModel(String id, String name) {
    }
}
