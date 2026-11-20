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
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.premiumcars.common.HttpCalls;

/**
 * A small S3 client for one Cloudflare R2 bucket (path-style URLs, Signature V4 by {@link SigV4}, region "auto"):
 * PUT, GET and DELETE of one object, each with a deadline for the whole exchange and three attempts on a server
 * error. Shared by the photo storage and the orders' documents storage.
 */
public class S3Client {

    /** An object read back: its bytes and the content type it was stored with (null when the bucket sent none). */
    public record S3Object(byte[] bytes, String contentType) {
    }

    /** The whole exchange (upload included) of one request; the request timeout covers only the headers. */
    private static final Duration DEADLINE = Duration.ofSeconds(120);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int ATTEMPTS = 3;

    private final String endpoint;
    private final String bucket;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final HttpClient http;

    public S3Client(String endpoint, String bucket, String accessKeyId, String secretAccessKey) {
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.bucket = bucket;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        // HTTP/1.1: the signed "host" header is then sent as it is
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** @param cacheControl the object's Cache-Control, null for none */
    public void put(String key, byte[] bytes, String contentType, String cacheControl) {
        HttpResponse<byte[]> response = send("PUT", key, bytes, contentType, cacheControl);
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException("R2 PUT " + key + ": HTTP " + response.statusCode() + " "
                    + snippet(response.body())));
        }
    }

    /** @throws UncheckedIOException when the object cannot be read (also when it is not there) */
    public S3Object get(String key) {
        HttpResponse<byte[]> response = send("GET", key, null, null, null);
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException("R2 GET " + key + ": HTTP " + response.statusCode()));
        }
        return new S3Object(response.body(), response.headers().firstValue("Content-Type").orElse(null));
    }

    /** @return the HTTP status (204 or 200 when removed, 404 when it was not there) */
    public int delete(String key) {
        return send("DELETE", key, null, null, null).statusCode();
    }

    private HttpResponse<byte[]> send(String method, String key, byte[] body, String contentType,
                                      String cacheControl) {
        String path = "/" + encode(bucket) + "/" + Arrays.stream(key.split("/")).map(S3Client::encode)
                .collect(Collectors.joining("/"));
        URI uri = URI.create(endpoint + path);
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = HttpCalls.send(http,
                        request(method, uri, path, body, contentType, cacheControl),
                        HttpResponse.BodyHandlers.ofByteArray(), DEADLINE);
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

    private HttpRequest request(String method, URI uri, String path, byte[] body, String contentType,
                                String cacheControl) {
        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
        String payloadHash = body == null ? SigV4.EMPTY_PAYLOAD_SHA256 : SigV4.sha256Hex(body);
        var signed = new TreeMap<String, String>();
        signed.put("host", uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort());
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        String authorization = SigV4.authorization(accessKeyId, secretAccessKey, "auto", "s3", method,
                path, "", signed, payloadHash, amzDate);

        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization);
        if (body != null) {
            b.header("Content-Type", contentType);
            if (cacheControl != null) {
                b.header("Cache-Control", cacheControl);
            }
        }
        return b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build();
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
