package com.premiumcars.order;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import com.premiumcars.config.AppProperties;
import com.premiumcars.media.S3Client;
import com.premiumcars.media.S3Client.S3Object;

/**
 * The orders' documents in a private Cloudflare R2 bucket ({@code app.media.r2-docs}). No public URL exists for them:
 * the admin panel gets the bytes through the backend, which checks the admin's session. Keys are
 * "orders/{orderId}/{uuid}.{ext}".
 */
public class R2OrderDocumentStorage implements OrderDocumentStorage {

    /** Never cached by a browser or a proxy. */
    private static final String CACHE_CONTROL = "private, no-store";

    private final S3Client s3;

    public R2OrderDocumentStorage(AppProperties.R2Docs cfg) {
        this.s3 = new S3Client(cfg.endpoint(), cfg.bucket(), cfg.accessKeyId(), cfg.secretAccessKey());
    }

    @Override
    public String put(long orderId, byte[] bytes, String contentType) {
        String key = "orders/" + orderId + "/" + UUID.randomUUID() + "." + DocumentFileType.extensionOf(contentType);
        s3.put(key, bytes, contentType, CACHE_CONTROL);
        return key;
    }

    @Override
    public StoredFile get(String storageKey) {
        S3Object object = s3.get(storageKey);
        return new StoredFile(object.bytes(), object.contentType() == null
                ? "application/octet-stream" : object.contentType());
    }

    @Override
    public void delete(String storageKey) {
        int status = s3.delete(storageKey);
        if (status != 200 && status != 204 && status != 404) {
            throw new UncheckedIOException(new IOException("R2 DELETE " + storageKey + ": HTTP " + status));
        }
    }
}
