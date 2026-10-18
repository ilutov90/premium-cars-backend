package com.premiumcars.social;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

/**
 * The Viber Channels Post API (chatapi.viber.com/pa): register the webhook once, find the channel's superadmin,
 * post messages as that superadmin. Every answer carries {@code status} (0 = ok) and {@code status_message}.
 * The channel token goes in the X-Viber-Auth-Token header, never in the URL or the body.
 */
@Component
public class ViberChannelClient {

    /** Viber's status code when no webhook was registered for the channel yet. */
    static final int WEBHOOK_NOT_SET = 10;

    private final String token;
    private final ObjectMapper json;
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Autowired
    public ViberChannelClient(AppProperties props, ObjectMapper json) {
        this(props.social().viberChannelToken(), json, URI.create("https://chatapi.viber.com/pa/"));
    }

    ViberChannelClient(String token, ObjectMapper json, URI base) {
        this.token = token;
        this.json = json;
        this.base = base;
    }

    /** Tells Viber where the channel's callbacks go; Viber calls that URL right away to check it. */
    public void setWebhook(String url) {
        call("set_webhook", Map.of("url", url), "Viber webhook");
    }

    /** The member id of the channel's (first) superadmin: channel posts are sent in their name. */
    public String superadminId() {
        JsonNode info = call("get_account_info", Map.of(), "Viber channel info");
        for (JsonNode member : info.path("members")) {
            if ("superadmin".equals(member.path("role").asText())) {
                return member.path("id").asText();
            }
        }
        throw new BadRequestException("Viber: the channel has no superadmin the token can post as");
    }

    /** A photo message (public JPEG URL); returns the message token. */
    public String postPicture(String from, String imageUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("type", "picture");
        body.put("text", "");
        body.put("media", imageUrl);
        return call("post", body, "Viber photo").path("message_token").asText();
    }

    /** A text message; returns the message token. */
    public String postText(String from, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("type", "text");
        body.put("text", text);
        return call("post", body, "Viber post").path("message_token").asText();
    }

    private JsonNode call(String method, Map<String, ?> body, String what) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(base.resolve(method))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("X-Viber-Auth-Token", token == null ? "" : token)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            int status = node.path("status").asInt(-1);
            if (r.statusCode() / 100 != 2 || status != 0) {
                String message = node.path("status_message").asText("HTTP " + r.statusCode());
                if (status == WEBHOOK_NOT_SET) {
                    message += " (connect the channel first: \"Connect Viber channel\" in the admin panel)";
                }
                throw new BadRequestException(what + " failed: " + message);
            }
            return node;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException(what + " interrupted", e);
        } catch (IOException e) {
            throw new BadRequestException(what + " failed: " + e.getMessage(), e);
        }
    }
}
