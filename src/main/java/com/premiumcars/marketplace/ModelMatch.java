package com.premiumcars.marketplace;

import java.util.List;

/**
 * Where one of our makes + models lands on a listing site.
 *
 * @param brand       the site's make (null when it has none for ours)
 * @param model       the site's model (null when none was found or chosen)
 * @param suggestions other models of the site's make that may be the same car, for the admin to choose from
 */
public record ModelMatch(Status status, SiteCatalog.SiteBrand brand, SiteCatalog.SiteModel model,
                         List<SiteCatalog.SiteModel> suggestions) {

    public enum Status {
        /** The site's lists were never read: nothing can be matched. */
        NO_CATALOG,
        /** The site has no such make: the car cannot be posted there. */
        BRAND_MISSING,
        /** The make is there, the model not, and nothing looks like it: an admin chooses. */
        MODEL_MISSING,
        /** Not the same name, but probably the same car (an export name, a longer/shorter name): to confirm. */
        SUGGESTED,
        /** The same name. */
        AUTO,
        /** Chosen by an admin (on the mapping page). */
        MANUAL,
        /** An admin said the site has no model for it: the car is not posted there. */
        UNAVAILABLE
    }

    public ModelMatch {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** A model can be sent to the site (an admin should still look at a suggestion). */
    public boolean usable() {
        return model != null && (status == Status.AUTO || status == Status.MANUAL || status == Status.SUGGESTED);
    }
}
