package com.premiumcars.importing.source.autowini;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.premiumcars.config.AppProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * autowini lets the posting script's requests (Python requests: its user agent, an accept-anything Accept header,
 * "Accept-Encoding: gzip, deflate") through and answered 403 to ours. The client must send the same headers and
 * read compressed answers.
 */
class AutowiniClientTest {

    static final String SCRIPT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private HttpServer server;
    private String base;
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    private final AutowiniClient client = new AutowiniClient(new AppProperties(null, null, null, null,
            new AppProperties.Importer(SCRIPT_UA, 5, 10, new AppProperties.Browser(false, 5),
                    new AppProperties.Autowini("user", "pass"))));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            var h = ex.getRequestHeaders();
            seen.put("ua", String.valueOf(h.getFirst("User-Agent")));
            seen.put("accept", String.valueOf(h.getFirst("Accept")));
            seen.put("encoding", String.valueOf(h.getFirst("Accept-Encoding")));
            boolean likeScript = SCRIPT_UA.equals(h.getFirst("User-Agent"))
                    && String.valueOf(h.getFirst("Accept-Encoding")).contains("gzip");
            byte[] html = "<html><dt>Make</dt><dd>Kia</dd> Корея</html>".getBytes(StandardCharsets.UTF_8);
            if (!likeScript) {
                ex.sendResponseHeaders(403, -1);
                ex.close();
                return;
            }
            boolean deflate = ex.getRequestURI().getPath().contains("deflate");
            byte[] body = deflate ? deflate(html) : gzip(html);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            ex.getResponseHeaders().add("Content-Encoding", deflate ? "deflate" : "gzip");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheScriptsHeadersAndReadsGzip() {
        AutowiniClient.Page page = client.get(base + "/Cars/IC1/cars-detail");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("<dd>Kia</dd>").contains("Корея");
        assertThat(seen).containsEntry("ua", SCRIPT_UA)
                .containsEntry("accept", "*/*")
                .containsEntry("encoding", "gzip, deflate");
    }

    @Test
    void readsDeflate() {
        assertThat(client.get(base + "/deflate").body()).contains("<dd>Kia</dd>");
    }

    private static byte[] gzip(byte[] data) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var d = new DeflaterOutputStream(out)) {
            d.write(data);
        }
        return out.toByteArray();
    }
}
