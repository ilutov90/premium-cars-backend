package com.premiumcars.importing;

import com.premiumcars.common.BadRequestException;

/**
 * The listing's page shows no price to import with (a guazi auction): nothing was saved. A bad request for an import
 * by hand (the admin may enter the price); the automatic import sets the listing aside (NO_PRICE).
 */
public class NoPriceException extends BadRequestException {

    public NoPriceException(String message) {
        super(message);
    }
}
