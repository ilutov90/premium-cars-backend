package com.premiumcars.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int ATTEMPTS = 3;

    private final AppProperties.R2 cfg;
    private final String endpoint;
    private final String publicUrl;
    private final HttpClient http;

    public R2ImageStorage(AppProperties.R2 cfg) {
        this.cfg = cfg;
        this.endpoint = cfg.endpoint().replaceAll("/+$", "");
        this.publicUrl = cfg.publicUrl().endsWith("/") ? cfg.publicUrl() : cfg.publicUrl() + "/";
        // HTTP/1.1: the signed "host" header is then sent as it is
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String store(String folder, byte[] bytes, String extension) {
        String key = folder + "/" + UUID.randomUUID() + "." + extension;
        put(key, bytes, contentType(extension));
        return key;
    }

    /** Uploads under the given key (also used to move local photos with their own names). */
    public void put(String key, byte[] bytes, String contentType) {
        HttpResponse<byte[]> response = send("PUT", key, bytes, contentType);
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException("R2 PUT " + key + ": HTTP " + response.statusCode() + " "
                    + snippet(response.body())));
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            int status = send("DELETE", storagePath, null, null).statusCode();
            if (status != 204 && status != 200) {
                log.warn("R2 DELETE {}: HTTP {}", storagePath, status);
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete {} from R2: {}", storagePath, e.toString());
        }
    }

    @Override
    public byte[] read(String storagePath) {
        HttpResponse<byte[]> response = send("GET", storagePath, null, null);
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException("R2 GET " + storagePath + ": HTTP "
                    + response.statusCode()));
        }
        return response.body();
    }

    @Override
    public String publicUrl(String storagePath) {
        return publicUrl + storagePath;
    }

    private HttpResponse<byte[]> send(String method, String key, byte[] body, String contentType) {
        String path = "/" + encode(cfg.bucket()) + "/" + Arrays.stream(key.split("/")).map(R2ImageStorage::encode)
                .collect(Collectors.joining("/"));
        URI uri = URI.create(endpoint + path);
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = http.send(request(method, uri, path, body, contentType),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 500 || attempt == ATTEMPTS) {
                    return response;
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during R2 " + method, e);
            }
            sleep(500L * attempt);
        }
        throw new UncheckedIOException("R2 " + method + " " + key + " failed", last);
    }

    private HttpRequest request(String method, URI uri, String path, byte[] body, String contentType) {
        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
        String payloadHash = body == null ? SigV4.EMPTY_PAYLOAD_SHA256 : SigV4.sha256Hex(body);
        var signed = new TreeMap<String, String>();
        signed.put("host", uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort());
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        String authorization = SigV4.authorization(cfg.accessKeyId(), cfg.secretAccessKey(), "auto", "s3", method,
                path, "", signed, payloadHash, amzDate);

        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization);
        if (body != null) {
            b.header("Content-Type", contentType)
                    // the key is unique per photo: browsers and Cloudflare may keep it for a year
                    .header("Cache-Control", "public, max-age=31536000, immutable");
        }
        return b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build();
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
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String snippet(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
