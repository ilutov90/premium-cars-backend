package com.premiumcars.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.sun.net.httpserver.HttpServer;

/** The inquiry e-mail, against a local fake of api.resend.com. */
class InquiryNotifierTest {

    record Call(String auth, JsonNode body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI endpoint;
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", ex -> {
            calls.add(new Call(ex.getRequestHeaders().getFirst("Authorization"), json.readTree(ex.getRequestBody())));
            byte[] answer = "{\"id\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, answer.length);
            ex.getResponseBody().write(answer);
            ex.close();
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/emails");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private InquiryNotifier notifier(String key, String to) {
        return new InquiryNotifier(key, to, "Premium Cars <noreply@premiumcarsimport.bg>",
                "https://premiumcarsimport.bg/", json, endpoint);
    }

    @Test
    void mailsTheInquiryWithTheCarAndALinkToTheAdminPanel() {
        var e = new InquiryCreatedEvent(12, "Иван", "0888123456", "ivan@example.com", "Има ли сервизна книжка?",
                "Hyundai Tucson 2023", "hyundai-tucson-2023-7");

        assertThat(notifier("re_test", "office@premiumcarsimport.bg, me@example.com").send(e)).isTrue();

        Call call = calls.getFirst();
        assertThat(call.auth()).isEqualTo("Bearer re_test");
        JsonNode mail = call.body();
        assertThat(mail.path("to")).extracting(JsonNode::asText)
                .containsExactly("office@premiumcarsimport.bg", "me@example.com");
        assertThat(mail.path("reply_to").asText()).isEqualTo("ivan@example.com");
        assertThat(mail.path("subject").asText()).isEqualTo("Ново запитване #12 — Hyundai Tucson 2023");
        assertThat(mail.path("text").asText())
                .contains("Име: Иван", "Телефон: 0888123456",
                        "Кола: Hyundai Tucson 2023 (https://premiumcarsimport.bg/cars/hyundai-tucson-2023-7)",
                        "Има ли сервизна книжка?", "https://premiumcarsimport.bg/admin/inquiries")
                .doesNotContain("re_test");
    }

    @Test
    void offWithoutAKeyOrAnAddress() {
        var e = new InquiryCreatedEvent(1, "A", "1", null, null, null, null);

        assertThat(notifier("", "office@premiumcarsimport.bg").send(e)).isFalse();
        assertThat(notifier("re_test", " ").send(e)).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void aFailingMailServiceIsOnlyLogged() {
        status = 500;
        var e = new InquiryCreatedEvent(2, "A", "1", null, null, null, null);

        assertThat(notifier("re_test", "office@premiumcarsimport.bg").send(e)).isFalse();
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().body().has("reply_to")).isFalse();
        assertThat(calls.getFirst().body().path("text").asText()).contains("общо запитване");
    }
}
