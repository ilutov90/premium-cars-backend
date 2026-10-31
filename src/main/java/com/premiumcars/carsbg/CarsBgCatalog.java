package com.premiumcars.carsbg;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.premiumcars.marketplace.Site;
import com.premiumcars.marketplace.SiteCatalog;

/**
 * cars.bg's makes and models with their ids (the "Публикувай обява" form's brand radios and
 * {@code carmodelpublish.php?brandId=} lists), as the browser extension reads them in an admin's browser. The server
 * never calls cars.bg; the last copy is kept.
 *
 * @param capturedAt when the extension read it (null when nothing was sent yet)
 */
public record CarsBgCatalog(@NotNull @Size(max = 1000) List<@Valid Brand> brands, Instant capturedAt) {

    public static final CarsBgCatalog EMPTY = new CarsBgCatalog(List.of(), null);

    public CarsBgCatalog {
        brands = brands == null ? List.of() : brands.stream().filter(Objects::nonNull).toList();
    }

    public boolean isEmpty() {
        return brands.isEmpty();
    }

    public SiteCatalog toSiteCatalog() {
        return new SiteCatalog(Site.CARS_BG, brands.stream()
                .map(b -> new SiteCatalog.SiteBrand(b.id(), b.name(), b.models().stream()
                        .map(m -> new SiteCatalog.SiteModel(m.id(), m.name())).toList()))
                .toList(), capturedAt);
    }

    public record Brand(@NotBlank @Pattern(regexp = "\\d{1,10}") String id, @NotBlank @Size(max = 100) String name,
                        @Size(max = 2000) List<@Valid Model> models) {
        public Brand {
            models = models == null ? List.of() : models.stream().filter(Objects::nonNull).toList();
        }
    }

    public record Model(@NotBlank @Pattern(regexp = "\\d{1,10}") String id, @NotBlank @Size(max = 100) String name) {
    }
}
