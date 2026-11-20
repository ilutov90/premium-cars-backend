package com.premiumcars.importing;

import com.premiumcars.common.BadRequestException;

/**
 * The source answered, but the link no longer shows a listing (autotrader.ca redirects a removed listing to a results
 * page). A bad request for an import by hand; callers that look after existing cars can tell it from a page that
 * could not be read.
 */
public class ListingGoneException extends BadRequestException {

    public ListingGoneException(String message) {
        super(message);
    }
}
