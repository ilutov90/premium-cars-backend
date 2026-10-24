package com.premiumcars.catalog.domain;

import java.util.List;
import java.util.Objects;

/**
 * One group of the source site's full specification sheet, as the site shows it (English names), e.g.
 * {@code "Engine"} with {@code "Maximum horsepower (Ps)" -> "300"}, or autowini's {@code "Interior Option"} with
 * {@code "Heating Seat" -> ""}. Kept as imported, for the car page; the admin panel does not edit it.
 *
 * @param items the rows; an empty value means "the car has it" (equipment without details)
 */
public record SpecGroup(String name, List<Item> items) {

    public static final int MAX_GROUPS = 60;
    public static final int MAX_ITEMS = 120;

    public SpecGroup {
        name = cut(name, 100);
        items = items == null ? List.of() : items.stream()
                .filter(Objects::nonNull)
                .filter(i -> i.name() != null)
                .limit(MAX_ITEMS)
                .toList();
    }

    public record Item(String name, String value) {
        public Item {
            name = cut(name, 200);
            value = value == null ? "" : cut(value, 500);
        }
    }

    /** Groups without a name or rows are dropped; at most {@link #MAX_GROUPS}. */
    public static List<SpecGroup> clean(List<SpecGroup> groups) {
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.name() != null && !g.items().isEmpty())
                .limit(MAX_GROUPS)
                .toList();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }
}
