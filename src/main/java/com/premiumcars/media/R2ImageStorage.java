package com.premiumcars.media;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.premiumcars.config.AppProperties;

/**
 * Car photos in a Cloudflare R2 bucket through its S3 API (path-style URLs, Signature V4, region "auto").
 * Browsers load them from the bucket's public custom domain ({@code publicUrl}); the backend reads them back only to
 * publish them. Storage paths are the object keys, e.g. "cars/15/3f2a.jpg". Created by {@link RoutingImageStorage}
 * when R2 is configured.
 */
public class R2ImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(R2ImageStorage.class);
    /** Photos are unique per key: browsers and Cloudflare may keep them for a year. */
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client s3;
    private final String publicUrl;

    public R2ImageStorage(AppProperties.R2 cfg) {
        this.s3 = new S3Client(cfg.endpoint(), cfg.bucket(), cfg.accessKeyId(), cfg.secretAccessKey());
        this.publicUrl = cfg.publicUrl().endsWith("/") ? cfg.publicUrl() : cfg.publicUrl() + "/";
    }

    @Override
    public String store(String folder, byte[] bytes, String extension) {
        String key = folder + "/" + UUID.randomUUID() + "." + extension;
        put(key, bytes, contentType(extension));
        return key;
    }

    /** Uploads under the given key (also used to move local photos with their own names). */
    public void put(String key, byte[] bytes, String contentType) {
        s3.put(key, bytes, contentType, CACHE_CONTROL);
    }

    @Override
    public void delete(String storagePath) {
        try {
            int status = s3.delete(storagePath);
            if (status != 204 && status != 200) {
                log.warn("R2 DELETE {}: HTTP {}", storagePath, status);
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete {} from R2: {}", storagePath, e.toString());
        }
    }

    @Override
    public byte[] read(String storagePath) {
        return s3.get(storagePath).bytes();
    }

    @Override
    public String publicUrl(String storagePath) {
        return publicUrl + storagePath;
    }

    static String contentType(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            default -> "image/jpeg";
        };
    }

    /** RFC 3986 encoding of one path segment, as S3 signs it. */
    static String encode(String segment) {
        return S3Client.encode(segment);
    }
}
