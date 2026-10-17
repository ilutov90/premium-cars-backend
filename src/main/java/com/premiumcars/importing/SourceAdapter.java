package com.premiumcars.importing;

import java.net.URI;

import com.premiumcars.catalog.domain.CarSource;

/**
 * One implementation per source site. To support a new site (encar.com, che168.com, a paid API ...),
 * add a Spring bean implementing this interface — nothing else in the import flow changes.
 */
public interface SourceAdapter {

    CarSource source();

    boolean supports(URI url);

    ImportedCar importFrom(URI url);
}
