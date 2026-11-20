package com.premiumcars.order;

/** Where the bytes of the orders' documents are kept: private, never reachable by a public URL. */
public interface OrderDocumentStorage {

    /** A stored file read back; {@code contentType} is what it was stored with. */
    record StoredFile(byte[] bytes, String contentType) {
    }

    /** @return the storage key, "orders/{orderId}/{uuid}.{ext}" */
    String put(long orderId, byte[] bytes, String contentType);

    /** @throws java.io.UncheckedIOException when the file cannot be read */
    StoredFile get(String storageKey);

    /** Removes the stored file; nothing happens for a key that is not there. */
    void delete(String storageKey);
}
