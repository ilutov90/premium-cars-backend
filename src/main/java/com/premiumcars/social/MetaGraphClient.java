package com.premiumcars.social;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

/**
 * The Meta Graph API calls of the posting script's FacebookPoster / InstagramPoster:
 * <ul>
 *   <li>Facebook: upload each photo unpublished to the page, then one feed post with them attached;</li>
 *   <li>Instagram: one container per photo (public URL), a carousel container with the caption, publish.</li>
 * </ul>
 * The token goes in the Authorization header, never in URLs (they may end up in logs).
 */
@Component
public class MetaGraphClient {

    private final AppProperties.Social cfg;
    private final ObjectMapper json;
    private final URI base;
    private final Duration pollInterval;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Autowired
    public MetaGraphClient(AppProperties props, ObjectMapper json) {
        this(props.social(), json, URI.create("https://graph.facebook.com/"), Duration.ofSeconds(2));
    }

    MetaGraphClient(AppProperties.Social cfg, ObjectMapper json, URI base, Duration pollInterval) {
        this.cfg = cfg;
        this.json = json;
        this.base = base;
        this.pollInterval = pollInterval;
    }

    // ---------------------------------------------------------------- Facebook

    /** Uploads a photo to the page without publishing it; returns its id for {@link #createPagePost}. */
    public String uploadUnpublishedPhoto(byte[] bytes, String extension) {
        String boundary = "----premiumcars" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        field(body, boundary, "published", "false");
        String type = "png".equals(extension) ? "image/png" : "image/jpeg";
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"source\"; filename=\"photo."
                + extension + "\"\r\nContent-Type: " + type + "\r\n\r\n");
        body.writeBytes(bytes);
        write(body, "\r\n--" + boundary + "--\r\n");
        HttpRequest request = request(cfg.facebookPageId() + "/photos")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return send(request, "Facebook photo upload").path("id").asText();
    }

    /** A page post with the uploaded photos attached; returns the post id. */
    public String createPagePost(String message, List<String> photoIds) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("message", message);
        for (int i = 0; i < photoIds.size(); i++) {
            form.put("attached_media[" + i + "]", "{\"media_fbid\":\"" + photoIds.get(i) + "\"}");
        }
        return send(post(cfg.facebookPageId() + "/feed", form), "Facebook post").path("id").asText();
    }

    public String facebookPermalink(String postId) {
        try {
            String url = send(get(postId + "?fields=permalink_url"), "Facebook permalink").path("permalink_url").asText();
            return url.isBlank() ? "https://www.facebook.com/" + postId : url;
        } catch (BadRequestException e) {
            return "https://www.facebook.com/" + postId;
        }
    }

    // ---------------------------------------------------------------- Instagram

    /** A photo container: a carousel item when {@code caption} is null, else a single-photo post. */
    public String instagramPhotoContainer(String imageUrl, String caption) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("image_url", imageUrl);
        if (caption == null) {
            form.put("is_carousel_item", "true");
        } else {
            form.put("caption", caption);
        }
        return send(post(cfg.instagramUserId() + "/media", form), "Instagram photo").path("id").asText();
    }

    public String instagramCarousel(List<String> children, String caption) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("media_type", "CAROUSEL");
        form.put("children", String.join(",", children));
        form.put("caption", caption);
        return send(post(cfg.instagramUserId() + "/media", form), "Instagram carousel").path("id").asText();
    }

    /** Instagram processes containers asynchronously; publishing too early fails. */
    public void waitUntilReady(String containerId) {
        for (int i = 0; i < 30; i++) {
            String status = send(get(containerId + "?fields=status_code"), "Instagram status")
                    .path("status_code").asText();
            if ("FINISHED".equals(status)) {
                return;
            }
            if ("ERROR".equals(status) || "EXPIRED".equals(status)) {
                throw new BadRequestException("Instagram could not process the photos (status " + status + ")");
            }
            sleep();
        }
        throw new BadRequestException("Instagram is still processing the photos; try again in a minute");
    }

    /** Publishes a container; returns the media id. */
    public String instagramPublish(String containerId) {
        return send(post(cfg.instagramUserId() + "/media_publish", Map.of("creation_id", containerId)),
                "Instagram publish").path("id").asText();
    }

    public String instagramPermalink(String mediaId) {
        try {
            String url = send(get(mediaId + "?fields=permalink"), "Instagram permalink").path("permalink").asText();
            return url.isBlank() ? null : url;
        } catch (BadRequestException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- HTTP

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(cfg.graphVersion() + "/" + path))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + cfg.accessToken());
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private JsonNode send(HttpRequest request, String what) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            if (r.statusCode() / 100 != 2 || node.has("error")) {
                String message = node.path("error").path("message").asText("HTTP " + r.statusCode());
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

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("Interrupted while waiting for Instagram", e);
        }
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream body, String s) {
        body.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
