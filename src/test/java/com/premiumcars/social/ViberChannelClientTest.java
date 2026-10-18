package com.premiumcars.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.common.BadRequestException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The Viber Channels Post API calls, against a local fake of chatapi.viber.com/pa. */
class ViberChannelClientTest {

    record Call(String path, String token, JsonNode body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ViberChannelClient viber;
    private volatile boolean webhookSet;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        viber = new ViberChannelClient("channel-token", json,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/pa/"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
        String path = ex.getRequestURI().getPath();
        calls.add(new Call(path, ex.getRequestHeaders().getFirst("X-Viber-Auth-Token"), body));
        String answer = switch (path) {
            case "/pa/set_webhook" -> {
                webhookSet = true;
                yield "{\"status\":0,\"status_message\":\"ok\",\"event_types\":[]}";
            }
            case "/pa/get_account_info" -> """
                    {"status":0,"status_message":"ok","id":"pa:channel","name":"Premium Cars",
                     "members":[{"id":"admin-1","name":"Helper","role":"admin"},
                                {"id":"owner-7","name":"Ivan","role":"superadmin"}]}""";
            case "/pa/post" -> webhookSet
                    ? "{\"status\":0,\"status_message\":\"ok\",\"message_token\":" + calls.size() + "}"
                    : "{\"status\":10,\"status_message\":\"webhookNotSet\"}";
            default -> "{\"status\":3,\"status_message\":\"badData\"}";
        };
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void postsPhotosAndTextAsTheChannelSuperadmin() {
        viber.setWebhook("https://cars.example/api/public/viber/webhook");
        String from = viber.superadminId();
        viber.postPicture(from, "https://i.ibb.co/x/1.jpg");
        String token = viber.postText(from, "🎯Kia Sportage\n#вноснаколи");

        assertThat(from).isEqualTo("owner-7");
        assertThat(token).isEqualTo("4");
        assertThat(calls).extracting(Call::path)
                .containsExactly("/pa/set_webhook", "/pa/get_account_info", "/pa/post", "/pa/post");
        assertThat(calls.get(0).body().path("url").asText()).isEqualTo("https://cars.example/api/public/viber/webhook");
        JsonNode picture = calls.get(2).body();
        assertThat(picture.path("type").asText()).isEqualTo("picture");
        assertThat(picture.path("media").asText()).isEqualTo("https://i.ibb.co/x/1.jpg");
        assertThat(picture.path("from").asText()).isEqualTo("owner-7");
        JsonNode text = calls.get(3).body();
        assertThat(text.path("type").asText()).isEqualTo("text");
        assertThat(text.path("text").asText()).isEqualTo("🎯Kia Sportage\n#вноснаколи");
        // the token travels in the header only
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.token()).isEqualTo("channel-token");
            assertThat(c.body().toString()).doesNotContain("channel-token");
        });
    }

    @Test
    void aMissingWebhookTellsTheAdminToConnectTheChannel() {
        assertThatThrownBy(() -> viber.postText("owner-7", "text"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Viber post failed: webhookNotSet")
                .hasMessageContaining("Connect Viber channel");
    }
}
