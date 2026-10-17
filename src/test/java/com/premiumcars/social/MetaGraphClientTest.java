package com.premiumcars.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The Graph API flows of the posting script, against a local fake of graph.facebook.com and imgbb. */
class MetaGraphClientTest {

    record Call(String method, String path, String query, String auth, String body) {
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger statusPolls = new AtomicInteger();
    private MetaGraphClient graph;
    private ImgbbUploader imgbb;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        var cfg = new AppProperties.Social("v23.0", "PAGE", "secret-token", "IGUSER", "imgbb-key", 10);
        graph = new MetaGraphClient(cfg, new ObjectMapper(), URI.create(base), Duration.ofMillis(10));
        imgbb = new ImgbbUploader("imgbb-key", new ObjectMapper(), URI.create(base + "imgbb/upload"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        calls.add(new Call(ex.getRequestMethod(), path, query, ex.getRequestHeaders().getFirst("Authorization"), body));
        String answer;
        int status = 200;
        if (path.equals("/v23.0/PAGE/photos")) {
            answer = "{\"id\":\"photo" + calls.size() + "\"}";
        } else if (path.equals("/v23.0/PAGE/feed")) {
            answer = body.contains("message=BAD") ? "{\"error\":{\"message\":\"Invalid OAuth access token.\"}}"
                    : "{\"id\":\"PAGE_99\"}";
            status = body.contains("message=BAD") ? 400 : 200;
        } else if (path.equals("/v23.0/PAGE_99")) {
            answer = "{\"permalink_url\":\"https://www.facebook.com/PAGE/posts/99\"}";
        } else if (path.equals("/v23.0/IGUSER/media")) {
            answer = "{\"id\":\"c" + calls.size() + "\"}";
        } else if (path.startsWith("/v23.0/c") && "fields=status_code".equals(query)) {
            answer = "{\"status_code\":\"" + (statusPolls.incrementAndGet() % 2 == 0 ? "FINISHED" : "IN_PROGRESS") + "\"}";
        } else if (path.equals("/v23.0/IGUSER/media_publish")) {
            answer = "{\"id\":\"media7\"}";
        } else if (path.equals("/v23.0/media7")) {
            answer = "{\"permalink\":\"https://www.instagram.com/p/abc/\"}";
        } else if (path.equals("/imgbb/upload")) {
            answer = "{\"data\":{\"url\":\"https://i.ibb.co/x/" + calls.size() + ".jpg\"}}";
        } else {
            answer = "{\"error\":{\"message\":\"unknown path " + path + "\"}}";
            status = 404;
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void facebookUploadsPhotosUnpublishedThenOnePostWithThemAttached() {
        String p1 = graph.uploadUnpublishedPhoto(new byte[]{1, 2, 3}, "jpg");
        String p2 = graph.uploadUnpublishedPhoto(new byte[]{4, 5, 6}, "jpg");
        String post = graph.createPagePost("🎯Kia Sportage\n#вноснаколи", List.of(p1, p2));

        assertThat(post).isEqualTo("PAGE_99");
        assertThat(graph.facebookPermalink(post)).isEqualTo("https://www.facebook.com/PAGE/posts/99");
        Call upload = calls.getFirst();
        assertThat(upload.body()).contains("name=\"published\"\r\n\r\nfalse").contains("name=\"source\"");
        Call feed = calls.get(2);
        String form = URLDecoder.decode(feed.body(), StandardCharsets.ISO_8859_1);
        assertThat(form).contains("attached_media[0]={\"media_fbid\":\"" + p1 + "\"}")
                .contains("attached_media[1]={\"media_fbid\":\"" + p2 + "\"}");
        // the token travels in the header, never in a URL
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.auth()).isEqualTo("Bearer secret-token");
            assertThat(String.valueOf(c.query())).doesNotContain("secret-token");
        });
    }

    @Test
    void instagramCarouselWaitsForTheContainersAndPublishes() {
        String a = graph.instagramPhotoContainer(imgbb.upload(new byte[]{1}), null);
        String b = graph.instagramPhotoContainer(imgbb.upload(new byte[]{2}), null);
        graph.waitUntilReady(a);
        graph.waitUntilReady(b);
        String carousel = graph.instagramCarousel(List.of(a, b), "caption");
        graph.waitUntilReady(carousel);

        assertThat(graph.instagramPublish(carousel)).isEqualTo("media7");
        assertThat(graph.instagramPermalink("media7")).isEqualTo("https://www.instagram.com/p/abc/");
        assertThat(calls).filteredOn(c -> c.path().equals("/v23.0/IGUSER/media"))
                .extracting(c -> URLDecoder.decode(c.body(), StandardCharsets.UTF_8))
                .anySatisfy(body -> assertThat(body).contains("is_carousel_item=true").contains("image_url=https://i.ibb.co/"))
                .anySatisfy(body -> assertThat(body).contains("media_type=CAROUSEL").contains("children=" + a + "," + b));
        assertThat(calls).filteredOn(c -> c.path().equals("/imgbb/upload"))
                .allSatisfy(c -> assertThat(c.body()).startsWith("key=imgbb-key&image="));
    }

    @Test
    void graphErrorsBecomeReadableMessages() {
        assertThatThrownBy(() -> graph.createPagePost("BAD", List.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Facebook post failed: Invalid OAuth access token.");
    }
}
