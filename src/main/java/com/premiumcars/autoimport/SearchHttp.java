package com.premiumcars.autoimport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.autoimport.SourceSearch.SearchException;

/**
 * JSON requests to one site's search API, paced: at least {@code pauseMs} between two requests (gentle on the site,
 * also when a preview and a run overlap). Retries twice on network errors, 429 and 5xx.
 */
final class SearchHttp {

    private static final int ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json;
    private final long pauseMs;
    private final Map<String, String> headers;
    private long last;

    SearchHttp(ObjectMapper json, long pauseMs, Map<String, String> headers) {
        this.json = json;
        this.pauseMs = pauseMs;
        this.headers = Map.copyOf(headers);
    }

    JsonNode get(URI uri) {
        return send(HttpRequest.newBuilder(uri).GET());
    }

    JsonNode post(URI uri, Object body) {
        try {
            return send(HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode send(HttpRequest.Builder builder) {
        headers.forEach(builder::header);
        HttpRequest request = builder.header("Accept", "application/json").timeout(Duration.ofSeconds(30)).build();
        String problem = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            pace();
            try {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = r.statusCode();
                if (status == 429 || status >= 500) {
                    problem = "HTTP " + status;
                    sleep(2000L * attempt);
                    continue;
                }
                return parse(r);
            } catch (IOException e) {
                problem = e.toString();
                sleep(2000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SearchException("Interrupted", e);
            }
        }
        throw new SearchException(request.uri().getHost() + " did not answer: " + problem);
    }

    private JsonNode parse(HttpResponse<String> r) {
        String body = r.body() == null ? "" : r.body();
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new SearchException(r.uri().getHost() + " answered HTTP " + r.statusCode() + " without data");
            }
            return node;
        } catch (JsonProcessingException e) {
            String start = body.strip();
            throw new SearchException(r.uri().getHost() + " answered HTTP " + r.statusCode()
                    + " with a page instead of data (a bot check?): "
                    + (start.length() > 200 ? start.substring(0, 200) : start));
        }
    }

    private synchronized void pace() {
        long wait = last + pauseMs - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        last = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchException("Interrupted", e);
        }
    }
}
