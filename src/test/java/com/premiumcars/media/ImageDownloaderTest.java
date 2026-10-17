package com.premiumcars.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.premiumcars.config.AppProperties;
import com.sun.net.httpserver.HttpServer;

/** Photo CDNs like imagebox.autowini.com answer 403 unless the request carries the logged-in session cookie. */
class ImageDownloaderTest {

    private HttpServer server;
    private String url;

    private final ImageDownloader downloader = new ImageDownloader(new AppProperties(null, null, null, null,
            new AppProperties.Importer("test-agent", 5, 10, new AppProperties.Browser(false, 5),
                    new AppProperties.Autowini(null, null))));

    @BeforeEach
    void start() throws Exception {
        byte[] jpeg = jpeg();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/photo_1024.jpg", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            boolean allowed = cookie != null && cookie.contains("SESSION=abc");
            byte[] body = allowed ? jpeg : "<html>Access denied</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", allowed ? "image/jpeg" : "text/html");
            exchange.sendResponseHeaders(allowed ? 200 : 403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // autowini answers photos with "Content-Type: image" (no subtype)
        server.createContext("/bare_1024.jpg", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image");
            exchange.sendResponseHeaders(200, jpeg.length);
            exchange.getResponseBody().write(jpeg);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/photo_1024.jpg";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void refusedWithoutSessionCookies() {
        assertThat(downloader.download(url, "https://www.autowini.com/")).isEmpty();
        assertThat(downloader.lastRefusal()).contains("HTTP 403").contains("Access denied");
    }

    @Test
    void downloadsWithTheLoggedInClient() {
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpCookie session = new HttpCookie("SESSION", "abc");
        session.setPath("/");
        session.setVersion(0);
        jar.getCookieStore().add(URI.create(url), session);
        HttpClient loggedIn = HttpClient.newBuilder().cookieHandler(jar).version(HttpClient.Version.HTTP_1_1).build();

        var image = downloader.download(url, "https://www.autowini.com/", loggedIn);

        assertThat(image).isPresent();
        assertThat(image.get().extension()).isEqualTo("jpg");
    }

    @Test
    void acceptsTheBareImageContentType() {
        String bare = url.replace("photo_1024.jpg", "bare_1024.jpg");
        var image = downloader.download(bare, "https://www.autowini.com/");
        assertThat(image).isPresent();
        assertThat(image.get().extension()).isEqualTo("jpg");
    }

    /** A minimal byte sequence that passes the JPEG checks (SOI ... EOI, over 1 KB). */
    private static byte[] jpeg() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0});
        out.writeBytes(new byte[2048]);
        out.writeBytes(new byte[]{(byte) 0xff, (byte) 0xd9});
        return out.toByteArray();
    }
}
