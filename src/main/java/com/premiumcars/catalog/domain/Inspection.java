package com.premiumcars.catalog.domain;

import java.util.List;

/**
 * The source's inspection report of a car, as imported (guazi: "reportDetailLite"); read-only like the spec sheet,
 * shown on the car page. Texts in the source's English (the frontend translates them).
 *
 * @param grade      the source's condition grade (guazi: S, A, B, C, D)
 * @param checks     yes/no checks such as "No Accident Damage"; {@code passed} = the car is free of it
 * @param categories every checked area with its numbers of normal and abnormal points
 * @param findings   the points with a defect: where, what the inspector found, a photo and a video of it
 */
public record Inspection(String grade, List<Check> checks, List<Category> categories, List<Finding> findings) {

    public Inspection {
        checks = checks == null ? List.of() : List.copyOf(checks);
        categories = categories == null ? List.of() : List.copyOf(categories);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public record Check(String title, boolean passed) {
    }

    public record Category(String name, int normal, int abnormal) {
    }

    /**
     * @param result the inspector's verdict, e.g. "Minor defect"
     * @param note   what exactly, e.g. "Rear bumper surface damage."
     */
    public record Finding(String category, String item, String result, String note, String imageUrl,
                          String videoUrl) {
    }

    public boolean isEmpty() {
        return grade == null && checks.isEmpty() && categories.isEmpty() && findings.isEmpty();
    }
}
