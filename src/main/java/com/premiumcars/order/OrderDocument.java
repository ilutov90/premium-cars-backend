package com.premiumcars.order;

import java.time.Instant;

/**
 * One row of the {@code order_document} table: a file of the order (its bytes are kept elsewhere).
 *
 * @param title      the document's own name, for {@link DocumentKind#OTHER}
 * @param storageKey where the bytes are stored ({@link OrderDocumentStorage}); null while none is stored
 */
public record OrderDocument(long id, long orderId, DocumentKind kind, String title, String fileName,
                            String contentType, Long sizeBytes, String storageKey, Instant uploadedAt,
                            String uploadedBy) {
}
