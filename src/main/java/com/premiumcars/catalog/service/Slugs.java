package com.premiumcars.catalog.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** "BMW X3 xDrive30i", 2022, id 15 -> "bmw-x3-xdrive30i-2022-15" */
final class Slugs {

    private Slugs() {
    }

    static String forCar(String make, String model, String title, Integer year, Long id) {
        String base = Stream.of(make, model)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
        if (base.isBlank()) {
            base = title == null ? "" : title;
        }
        String slug = slugify(base + (year != null ? " " + year : ""));
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return (slug.isEmpty() ? "car" : slug) + "-" + id;
    }

    static String slugify(String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}
