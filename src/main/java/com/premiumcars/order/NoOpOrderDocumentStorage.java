package com.premiumcars.order;

import org.springframework.stereotype.Component;

/** Stands in while documents have no file storage: there is nothing to remove. */
@Component
public class NoOpOrderDocumentStorage implements OrderDocumentStorage {

    @Override
    public void delete(String storageKey) {
        // no files are stored yet
    }
}
