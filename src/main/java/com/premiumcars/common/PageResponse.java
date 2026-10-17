package com.premiumcars.common;

import java.util.List;
import java.util.function.Function;

/** Stable JSON shape for paged results. {@code page} is 0-based. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageResponse<T> of(List<E> rows, int page, int size, long total, Function<E, T> mapper) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(rows.stream().map(mapper).toList(), page, size, total, totalPages);
    }
}
