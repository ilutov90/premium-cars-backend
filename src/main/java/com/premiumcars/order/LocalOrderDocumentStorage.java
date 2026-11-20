package com.premiumcars.order;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The orders' documents in a local folder, used while the private R2 bucket is not configured. The folder must not be
 * the one {@code /media} serves (checked when the bean is made, see {@link OrderDocumentStorageConfig}).
 */
public class LocalOrderDocumentStorage implements OrderDocumentStorage {

    private final Path root;

    public LocalOrderDocumentStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String put(long orderId, byte[] bytes, String contentType) {
        String key = "orders/" + orderId + "/" + UUID.randomUUID() + "." + DocumentFileType.extensionOf(contentType);
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store " + key, e);
        }
        return key;
    }

    @Override
    public StoredFile get(String storageKey) {
        Path file = resolve(storageKey);
        try {
            return new StoredFile(Files.readAllBytes(file), contentTypeOf(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (NoSuchFileException e) {
            // already gone
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + storageKey, e);
        }
    }

    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("Bad storage key: " + key);
        }
        return file;
    }

    private static String contentTypeOf(String key) {
        String ext = key.substring(key.lastIndexOf('.') + 1);
        for (DocumentFileType t : DocumentFileType.values()) {
            if (t.extension().equals(ext)) {
                return t.contentType();
            }
        }
        return "application/octet-stream";
    }
}
