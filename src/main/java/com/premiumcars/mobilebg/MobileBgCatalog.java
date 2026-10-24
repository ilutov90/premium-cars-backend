package com.premiumcars.mobilebg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * mobile.bg's lists for the "Добави обява" form (category "Автомобили и Джипове"), as the browser extension reads
 * them from the form itself: brands with their models (the page's {@code cmm} array), the options of the selects
 * (by field name, e.g. {@code f17} = colours) and the extras checkboxes. The server never calls mobile.bg; the
 * last copy an admin's browser sent is kept, so the admin panel can offer mobile.bg's own values.
 *
 * @param capturedAt when the extension read it (null when nothing was sent yet)
 */
public record MobileBgCatalog(
        @NotNull @Size(max = 1000) List<@Valid Brand> brands,
        @NotNull @Size(max = 100) Map<String, @Size(max = 500) List<@Valid Option>> selects,
        @NotNull @Size(max = 300) List<@Valid Extra> extras,
        Instant capturedAt
) {

    public static final MobileBgCatalog EMPTY = new MobileBgCatalog(List.of(), Map.of(), List.of(), null);

    public MobileBgCatalog {
        brands = brands == null ? List.of() : brands.stream().filter(Objects::nonNull).toList();
        selects = selects == null ? Map.of() : selects;
        extras = extras == null ? List.of() : extras.stream().filter(Objects::nonNull).toList();
    }

    public boolean isEmpty() {
        return brands.isEmpty();
    }

    /** A select's options; empty when the catalog does not have that field. */
    public List<Option> options(String field) {
        return selects.getOrDefault(field, List.of());
    }

    public record Brand(@NotBlank @Size(max = 100) String name, @Size(max = 2000) List<@Size(max = 100) String> models) {
        public Brand {
            models = models == null ? List.of() : models.stream().filter(Objects::nonNull).toList();
        }
    }

    public record Option(@Size(max = 200) String value, @Size(max = 200) String text) {
    }

    /** @param field e.g. "f70"; @param value the checkbox's value, e.g. "Климатик" */
    public record Extra(@Size(max = 20) String field, @Size(max = 200) String value) {
    }
}
