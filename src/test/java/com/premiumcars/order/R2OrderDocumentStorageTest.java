package com.premiumcars.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.premiumcars.config.AppProperties;
import com.sun.net.httpserver.HttpServer;

class R2OrderDocumentStorageTest {

    record Seen(String method, String path, Map<String, String> headers) {
    }

    private HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private R2OrderDocumentStorage storage;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
            String path = ex.getRequestURI().getRawPath();
            seen.add(new Seen(ex.getRequestMethod(), path, h));
            byte[] body = ex.getRequestBody().readAllBytes();
            switch (ex.getRequestMethod()) {
                case "PUT" -> {
                    objects.put(path, body);
                    ex.sendResponseHeaders(200, -1);
                }
                case "GET" -> {
                    byte[] stored = objects.get(path);
                    if (stored == null) {
                        ex.sendResponseHeaders(404, -1);
                    } else {
                        ex.getResponseHeaders().add("Content-Type", "application/pdf");
                        ex.sendResponseHeaders(200, stored.length);
                        ex.getResponseBody().write(stored);
                    }
                }
                default -> {
                    ex.sendResponseHeaders(objects.remove(path) == null ? 404 : 204, -1);
                }
            }
            ex.close();
        });
        server.start();
        storage = new R2OrderDocumentStorage(new AppProperties.R2Docs("http://127.0.0.1:" + server.getAddress()
                .getPort() + "/", "premium-cars-documents", "docs-key", "docs-secret"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void testPut_Pdf_SignedPrivateRequestUnderOrderKey() {
        String key = storage.put(42, "%PDF-1.4".getBytes(), "application/pdf");

        assertThat(key).matches("orders/42/[0-9a-f-]{36}\\.pdf");
        Seen put = seen.getFirst();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/premium-cars-documents/" + key);
        assertThat(put.headers().get("authorization")).startsWith("AWS4-HMAC-SHA256 Credential=docs-key/")
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
        assertThat(put.headers().get("cache-control")).isEqualTo("private, no-store");
        assertThat(put.headers().get("content-type")).isEqualTo("application/pdf");
        assertThat(put.headers().get("x-amz-content-sha256")).hasSize(64);
    }

    @Test
    void testGet_StoredFile_BytesAndContentType() {
        String key = storage.put(7, new byte[] {1, 2, 3}, "application/pdf");

        var file = storage.get(key);

        assertThat(file.bytes()).containsExactly(1, 2, 3);
        assertThat(file.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void testDelete_StoredAndMissingFile_NoError() {
        String key = storage.put(7, new byte[] {1}, "image/jpeg");
        storage.delete(key);
        storage.delete(key);
        assertThat(seen.stream().filter(s -> s.method().equals("DELETE"))).hasSize(2);
        assertThat(objects).isEmpty();
    }
}
