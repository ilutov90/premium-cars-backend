package com.premiumcars.order;

/** Where the bytes of the orders' documents are kept. */
public interface OrderDocumentStorage {

    /** Removes the stored file; nothing happens for a key that is not there. */
    void delete(String storageKey);
}
