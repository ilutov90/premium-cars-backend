package com.premiumcars.social;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

/**
 * Instagram fetches photos from public URLs, and the site's photos are not public while it runs locally.
 * Like the posting script, photos are uploaded to imgbb (free image hosting) first.
 */
@Component
public class ImgbbUploader {

    private final String apiKey;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Autowired
    public ImgbbUploader(AppProperties props, ObjectMapper json) {
        this(props.social().imgbbApiKey(), json, URI.create("https://api.imgbb.com/1/upload"));
    }

    ImgbbUploader(String apiKey, ObjectMapper json, URI endpoint) {
        this.apiKey = apiKey;
        this.json = json;
        this.endpoint = endpoint;
    }

    /** @return the public URL of the uploaded photo */
    public String upload(byte[] bytes) {
        String body = "key=" + enc(apiKey) + "&image=" + enc(Base64.getEncoder().encodeToString(bytes));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = json.readTree(r.body());
            String url = node.path("data").path("url").asText("");
            if (r.statusCode() != 200 || url.isBlank()) {
                throw new BadRequestException("imgbb upload failed: "
                        + node.path("error").path("message").asText("HTTP " + r.statusCode()));
            }
            return url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("imgbb upload interrupted", e);
        } catch (IOException e) {
            throw new BadRequestException("imgbb upload failed: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
