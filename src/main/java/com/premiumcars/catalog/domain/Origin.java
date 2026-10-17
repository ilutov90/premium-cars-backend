package com.premiumcars.catalog.domain;

/** Country of origin shown to customers. Derived from the source, so the source site itself stays hidden. */
public enum Origin {
    KR(CarSource.AUTOWINI),
    CN(CarSource.AUTOHOME);

    private final CarSource source;

    Origin(CarSource source) {
        this.source = source;
    }

    public CarSource source() {
        return source;
    }

    public static Origin of(CarSource source) {
        for (Origin o : values()) {
            if (o.source == source) {
                return o;
            }
        }
        return null;
    }
}
