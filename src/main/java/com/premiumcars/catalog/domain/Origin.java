package com.premiumcars.catalog.domain;

import java.util.List;

/** Country of origin shown to customers. Derived from the source, so the source site itself stays hidden. */
public enum Origin {
    KR(CarSource.AUTOWINI),
    CN(CarSource.AUTOHOME, CarSource.GUAZI);

    private final List<CarSource> sources;

    Origin(CarSource... sources) {
        this.sources = List.of(sources);
    }

    /** The source sites of this country. */
    public List<CarSource> sources() {
        return sources;
    }

    public static Origin of(CarSource source) {
        for (Origin o : values()) {
            if (o.sources.contains(source)) {
                return o;
            }
        }
        return null;
    }
}
