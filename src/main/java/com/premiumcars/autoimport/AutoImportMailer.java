package com.premiumcars.autoimport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * E-mails the admin when the automatic import stopped itself (too many cars would have been removed), through Resend
 * with the inquiry e-mails' settings ({@code app.notify}). Bulgarian, like the inquiry e-mail. Off without them.
 */
@Component
public class AutoImportMailer {

    private static final Logger log = LoggerFactory.getLogger(AutoImportMailer.class);

    private final String apiKey;
    private final String to;
    private final String from;
    private final String siteUrl;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AutoImportMailer(@Value("${app.notify.resend-api-key:}") String apiKey,
                            @Value("${app.notify.to:}") String to,
                            @Value("${app.notify.from:}") String from,
                            @Value("${app.notify.site-url:}") String siteUrl,
                            ObjectMapper json) {
        this.apiKey = apiKey;
        this.to = to;
        this.from = from;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
        this.json = json;
    }

    /** "The run stopped": {@code reason} in the admin's words. */
    public void stopped(String sourceName, String reason) {
        if (blank(apiKey) || blank(to) || blank(from)) {
            return;
        }
        String text = "Автоматичният импорт от " + sourceName + " спря, без да маха коли.\n\n" + reason
                + "\n\nПровери отчета в админ панела: " + siteUrl + "/admin/auto-import\n"
                + "Ако всичко е наред (например нарочно си стеснил филтрите), пусни импорта ръчно от там.";
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("from", from);
        mail.put("to", List.of(to.split("\\s*,\\s*")));
        mail.put("subject", "Автоматичният импорт от " + sourceName + " спря");
        mail.put("text", text);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mail), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                log.warn("Auto-import e-mail: Resend answered HTTP {}: {}", r.statusCode(), r.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Auto-import e-mail not sent: {}", e.toString());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
