package com.premiumcars.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class HttpCallsTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/stalls", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            OutputStream body = exchange.getResponseBody();
            body.write(new byte[10]);
            body.flush();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            byte[] text = "fine".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, text.length);
            exchange.getResponseBody().write(text);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void testSend_BodyStopsComing_ThrowsTimeoutAtTheDeadline() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(uri("/stalls")).timeout(Duration.ofSeconds(30)).build();
        long start = System.nanoTime();

        assertThatThrownBy(() -> HttpCalls.send(client, request, HttpResponse.BodyHandlers.ofByteArray(),
                Duration.ofMillis(500)))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void testSend_CompleteAnswer_ReturnsIt() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(uri("/ok")).build();

        HttpResponse<String> r = HttpCalls.send(client, request, HttpResponse.BodyHandlers.ofString(),
                Duration.ofSeconds(5));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("fine");
    }
}
