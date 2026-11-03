package com.premiumcars.importing;

import com.premiumcars.common.BadRequestException;

/**
 * The source site refused us (HTTP 403 or 429: its bot protection, usually for too many requests from the server's
 * address), not the listing. For an import by hand a bad request like any other; the automatic import keeps the
 * listing queued and pauses that source's queue for a while instead of counting a failure.
 */
public class SourceBlockedException extends BadRequestException {

    private final int status;

    public SourceBlockedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public SourceBlockedException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static boolean blocking(int status) {
        return status == 403 || status == 429;
    }
}
