package com.premiumcars.autoimport;

import java.time.Instant;
import java.util.List;

public final class RemovedReportDtos {

    private RemovedReportDtos() {
    }

    /** Only the grouping's columns are set; the others are null. */
    public record Row(String make, String model, Integer year, int count, double sharePct, Long avgPriceEur,
                      Double avgDaysListed) {
    }

    /** @param weekStart the Monday (Europe/Sofia) as yyyy-MM-dd */
    public record Week(String weekStart, int count) {
    }

    /** The answer to a reset: the new moment from which removals count. */
    public record StatsReset(Instant statsFrom) {
    }

    /**
     * @param total        rows counted (makes in our import filters only)
     * @param unrecognized rows of the same period and source without a make
     * @param statsFrom    the last reset: only cars removed since then are counted; null = never reset
     */
    public record RemovedReport(int total, int unrecognized, Instant firstRemovedAt, Instant statsFrom, List<Row> rows,
                                List<Week> weeks, List<String> makes, List<String> models) {
    }
}
