package com.premiumcars.order;

import com.premiumcars.common.BadRequestException;

/**
 * No place for the documents yet (production before the private R2 bucket is set up): every upload and download is
 * refused with a message, so a document is never kept where a deploy would lose it.
 */
final class UnconfiguredOrderDocumentStorage implements OrderDocumentStorage {

    private static final String MESSAGE = "The storage for order documents is not configured: set up the private R2 "
            + "bucket (R2_DOCS_BUCKET, R2_DOCS_ACCESS_KEY_ID, R2_DOCS_SECRET_ACCESS_KEY)";

    @Override
    public String put(long orderId, byte[] bytes, String contentType) {
        throw new BadRequestException(MESSAGE);
    }

    @Override
    public StoredFile get(String key) {
        throw new BadRequestException(MESSAGE);
    }

    @Override
    public void delete(String key) {
        // nothing was ever stored here
    }
}
