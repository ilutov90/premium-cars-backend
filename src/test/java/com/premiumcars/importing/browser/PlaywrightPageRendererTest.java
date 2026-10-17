package com.premiumcars.importing.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.browser.PageRenderer.BrowserCookie;
import com.premiumcars.importing.browser.PageRenderer.BrowserImages;
import com.premiumcars.importing.browser.PageRenderer.CapturedImage;
import com.premiumcars.media.ImageDownloader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A photo server that behaves like imagebox.autowini.com: 403 with an HTML page unless the request comes from an
 * {@code <img>} in a browser ({@code Sec-Fetch-Dest: image}) with the session cookie. Needs Chromium (downloaded by
 * Playwright on first use); skipped when it cannot start.
 */
class PlaywrightPageRendererTest {

    private static boolean browserAvailable;

    private HttpServer server;
    private String base;
    private final byte[] jpeg = jpeg();

    private final PlaywrightPageRenderer renderer = new PlaywrightPageRenderer(new AppProperties(null, null, null, null,
            new AppProperties.Importer("Mozilla/5.0 test", 10, 30, new AppProperties.Browser(true, 30),
                    new AppProperties.Autowini(null, null))));

    @BeforeAll
    static void checkBrowser() {
        try (Playwright playwright = Playwright.create()) {
            playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)).close();
            browserAvailable = true;
        } catch (RuntimeException e) {
            browserAvailable = false;
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Cars/IC1/cars-detail", ex -> send(ex, 200, "text/html",
                ("<html><body><h1>Listing</h1><img src='/upload/g1_1024.jpg'>"
                        + "<div style='height:3000px'></div><img loading='lazy' src='/upload/g2_1024.jpg'>"
                        + "<img src='/static/logo.png'></body></html>").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/upload/", ex -> {
            boolean fromImg = "image".equals(ex.getRequestHeaders().getFirst("Sec-Fetch-Dest"));
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            boolean session = cookie != null && cookie.contains("SESSION=abc");
            boolean exists = !ex.getRequestURI().getPath().endsWith("b_1024.jpg"); // only the 320 px version of b
            if (fromImg && session && exists) {
                send(ex, 200, "image/jpeg", jpeg);
            } else {
                send(ex, 403, "text/html", "<!DOCTYPE html><html>autowini</html>".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void loadsPhotosLikeABrowserWithTheSessionCookies() {
        assumeTrue(browserAvailable, "Chromium is not available");

        Map<Integer, byte[]> photos = renderer.loadImages(base + "/Cars/IC1/cars-detail",
                List.of(List.of(base + "/upload/a_1024.jpg", base + "/upload/a_320.jpg"),
                        List.of(base + "/upload/b_1024.jpg", base + "/upload/b_320.jpg"),
                        List.of(base + "/upload/c_1024.jpg")),
                List.of(new BrowserCookie("SESSION", "abc", "127.0.0.1", "/")), null).requested();

        assertThat(photos).containsOnlyKeys(0, 1, 2);
        assertThat(photos.values()).allSatisfy(bytes -> assertThat(ImageDownloader.accept(bytes)).isPresent());
    }

    @Test
    void withoutTheSessionNothingLoads() {
        assumeTrue(browserAvailable, "Chromium is not available");

        BrowserImages photos = renderer.loadImages(base + "/Cars/IC1/cars-detail",
                List.of(List.of(base + "/upload/a_1024.jpg")), List.of(), Pattern.compile("/upload/"));

        assertThat(photos.requested()).isEmpty();
        assertThat(photos.pageImages()).isEmpty();
    }

    @Test
    void keepsThePhotosTheGalleryLoadsEvenWhenTheParsedUrlsAreWrong() {
        assumeTrue(browserAvailable, "Chromium is not available");

        BrowserImages photos = renderer.loadImages(base + "/Cars/IC1/cars-detail",
                List.of(List.of(base + "/upload/b_1024.jpg")), // does not exist
                List.of(new BrowserCookie("SESSION", "abc", "127.0.0.1", "/")), Pattern.compile("/upload/"));

        assertThat(photos.requested()).isEmpty();
        assertThat(photos.pageImages()).extracting(CapturedImage::url)
                .containsExactly(base + "/upload/g1_1024.jpg", base + "/upload/g2_1024.jpg"); // lazy one too, no logo
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static byte[] jpeg() {
        try {
            BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            g.setColor(new Color(200, 30, 30));
            g.fillRect(0, 0, 400, 300);
            g.dispose();
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
