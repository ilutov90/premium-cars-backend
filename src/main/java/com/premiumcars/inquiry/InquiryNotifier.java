package com.premiumcars.inquiry;

import java.io.IOException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * E-mails the admin about every new inquiry through Resend (resend.com). Runs after the inquiry is committed and
 * on another thread, so a slow or failing mail service never loses an inquiry or slows down the form; failures are
 * only logged (the inquiry is in the admin panel anyway). Off while RESEND_API_KEY or APP_NOTIFY_TO is empty.
 * The mail is for the business (not a customer), so its text is Bulgarian here.
 */
@Component
public class InquiryNotifier {

    private static final Logger log = LoggerFactory.getLogger(InquiryNotifier.class);

    private final String apiKey;
    private final String to;
    private final String from;
    private final String siteUrl;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public InquiryNotifier(@Value("${app.notify.resend-api-key:}") String apiKey,
                           @Value("${app.notify.to:}") String to,
                           @Value("${app.notify.from:}") String from,
                           @Value("${app.notify.site-url:}") String siteUrl,
                           ObjectMapper json) {
        this(apiKey, to, from, siteUrl, json, URI.create("https://api.resend.com/emails"));
    }

    InquiryNotifier(String apiKey, String to, String from, String siteUrl, ObjectMapper json, URI endpoint) {
        this.apiKey = apiKey;
        this.to = to;
        this.from = from;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
        this.json = json;
        this.endpoint = endpoint;
    }

    public boolean enabled() {
        return notBlank(apiKey) && notBlank(to) && notBlank(from);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInquiry(InquiryCreatedEvent e) {
        send(e);
    }

    /** @return true when Resend accepted the mail */
    boolean send(InquiryCreatedEvent e) {
        if (!enabled()) {
            return false;
        }
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("from", from);
        mail.put("to", List.of(to.split("\\s*,\\s*")));
        mail.put("subject", subject(e));
        mail.put("text", text(e));
        if (notBlank(e.email())) {
            mail.put("reply_to", e.email());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mail), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                log.warn("Inquiry #{}: Resend answered HTTP {}: {}", e.id(), r.statusCode(), r.body());
                return false;
            }
            log.info("Inquiry #{}: e-mail sent", e.id());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException ex) {
            log.warn("Inquiry #{}: e-mail not sent: {}", e.id(), ex.toString());
            return false;
        }
    }

    static String subject(InquiryCreatedEvent e) {
        return "Ново запитване #" + e.id() + (e.carTitle() == null ? "" : " — " + e.carTitle());
    }

    String text(InquiryCreatedEvent e) {
        StringBuilder t = new StringBuilder("Ново запитване от сайта.\n\n");
        t.append("Име: ").append(e.name()).append('\n');
        if (notBlank(e.phone())) {
            t.append("Телефон: ").append(e.phone()).append('\n');
        }
        if (notBlank(e.email())) {
            t.append("Имейл: ").append(e.email()).append('\n');
        }
        if (e.carTitle() != null) {
            t.append("Кола: ").append(e.carTitle());
            if (notBlank(siteUrl) && e.carSlug() != null) {
                t.append(" (").append(siteUrl).append("/cars/").append(e.carSlug()).append(')');
            }
            t.append('\n');
        } else {
            t.append("Кола: общо запитване (\"намери ми кола\")\n");
        }
        if (notBlank(e.message())) {
            t.append("\nСъобщение:\n").append(e.message()).append('\n');
        }
        if (notBlank(siteUrl)) {
            t.append("\nВсички запитвания: ").append(siteUrl).append("/admin/inquiries\n");
        }
        return t.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
