package com.premiumcars.catalog.domain;

/** Where a listing was imported from. Add new values when a new source adapter is added. */
public enum CarSource {
    AUTOHOME,
    AUTOWINI,
    /** en.guazi.com (China); priced with the autohome formula. */
    GUAZI,
    /** www.autotrader.ca (Canada, used cars, prices in CAD); read with Trader Corporation's permission (Nov 2026). */
    AUTOTRADER_CA,
    OTHER,
    MANUAL
}
