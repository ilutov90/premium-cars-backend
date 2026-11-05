package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.autoimport.SourceSearch.SearchException;
import com.premiumcars.importing.browser.BrowserFetch;
import com.sun.net.httpserver.HttpServer;

/** A site that refuses the server is asked from the browser, and then through the browser only. Local server only. */
class SearchHttpTest {

    private HttpServer server;
    private final AtomicInteger direct = new AtomicInteger();
    private volatile int status = 403;
    private volatile String answer = "<!DOCTYPE html><html><title> </title>Oops! Your access has been limited</html>";

    /** The browser: answers with data, remembers what it was asked. */
    private static final class FakeBrowser implements BrowserFetch {
        final List<String> asked = new ArrayList<>();
        boolean on = true;

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        public Answer fetch(String startUrl, String method, String url, String body, Map<String, String> headers) {
            asked.add(startUrl + " " + method + " " + url + (body == null ? "" : " " + body));
            return new Answer(200, "{\"result\":\"SUCCESS\",\"data\":55306}");
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            direct.incrementAndGet();
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void aRefusalGoesToTheBrowserAndStaysThere() {
        FakeBrowser browser = new FakeBrowser();
        SearchHttp http = new SearchHttp(new ObjectMapper(), 0, Map.of(), browser, "https://m.example.com/");

        assertThat(http.get(uri("/items/cars/count?make=C0810")).path("data").asInt()).isEqualTo(55306);
        assertThat(direct.get()).isEqualTo(1);
        assertThat(http.viaBrowser()).isTrue();

        http.post(uri("/search"), Map.of("brandId", "1212"));
        assertThat(direct.get()).isEqualTo(1); // not asked directly again
        assertThat(browser.asked).containsExactly(
                "https://m.example.com/ GET " + uri("/items/cars/count?make=C0810"),
                "https://m.example.com/ POST " + uri("/search") + " {\"brandId\":\"1212\"}");
    }

    @Test
    void aBotCheckPageWithStatus200IsARefusalToo() {
        status = 200;
        answer = "<!doctype html><title>Security Verification</title>";
        FakeBrowser browser = new FakeBrowser();
        SearchHttp http = new SearchHttp(new ObjectMapper(), 0, Map.of(), browser, "https://en.example.com/used-cars/");
        assertThat(http.get(uri("/list")).path("result").asText()).isEqualTo("SUCCESS");
        assertThat(browser.asked).hasSize(1);
    }

    @Test
    void dataIsTakenDirectlyAndWithoutABrowserARefusalStaysAnError() {
        status = 200;
        answer = "{\"code\":0,\"data\":{\"totalCount\":7}}";
        FakeBrowser browser = new FakeBrowser();
        SearchHttp http = new SearchHttp(new ObjectMapper(), 0, Map.of(), browser, "https://m.example.com/");
        assertThat(http.get(uri("/x")).path("data").path("totalCount").asInt()).isEqualTo(7);
        assertThat(browser.asked).isEmpty();
        assertThat(http.viaBrowser()).isFalse();

        status = 403;
        answer = "<html>refused</html>";
        browser.on = false;
        assertThatThrownBy(() -> http.get(uri("/x"))).isInstanceOf(SearchException.class)
                .hasMessageContaining("HTTP 403 with a page instead of data");
        SearchHttp noBrowser = new SearchHttp(new ObjectMapper(), 0, Map.of());
        assertThatThrownBy(() -> noBrowser.get(uri("/x"))).isInstanceOf(SearchException.class);
    }
}
