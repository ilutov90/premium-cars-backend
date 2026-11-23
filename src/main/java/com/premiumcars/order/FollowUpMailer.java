package com.premiumcars.order;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.order.OrderRepository.OrderRow;

/**
 * Every morning (08:30 Sofia) e-mails the admin the customers to contact today or earlier ("Следващ контакт"),
 * through Resend like the inquiry e-mails. Nothing is sent when there are none, while RESEND_API_KEY / APP_NOTIFY_TO
 * is empty, or where background jobs are off (a laptop worker). The mail is for the business: Bulgarian text.
 */
@Component
public class FollowUpMailer {

    private static final Logger log = LoggerFactory.getLogger(FollowUpMailer.class);
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(SOFIA);
    static final int MAX_LISTED = 50;

    private final OrderService service;
    private final OrderRepository orders;
    private final String apiKey;
    private final String to;
    private final String from;
    private final String siteUrl;
    private final boolean backgroundJobs;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FollowUpMailer(OrderService service, OrderRepository orders,
                          @Value("${app.notify.resend-api-key:}") String apiKey,
                          @Value("${app.notify.to:}") String to,
                          @Value("${app.notify.from:}") String from,
                          @Value("${app.notify.site-url:}") String siteUrl,
                          @Value("${app.background-jobs:true}") boolean backgroundJobs,
                          ObjectMapper json) {
        this.service = service;
        this.orders = orders;
        this.apiKey = apiKey;
        this.to = to;
        this.from = from;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
        this.backgroundJobs = backgroundJobs;
        this.json = json;
    }

    @Scheduled(cron = "${app.orders.follow-up-cron:0 30 8 * * *}", zone = "Europe/Sofia")
    public void morning() {
        if (!backgroundJobs || blank(apiKey) || blank(to) || blank(from)) {
            return;
        }
        List<OrderRow> due = orders.findFollowUps(service.endOfToday(), MAX_LISTED);
        if (due.isEmpty()) {
            return;
        }
        send("Клиенти за контакт днес: " + due.size(), text(due, siteUrl));
    }

    /** One line per order: when, who, phone, the car or how many cars are offered, the link. */
    static String text(List<OrderRow> due, String siteUrl) {
        StringBuilder b = new StringBuilder("Клиенти, с които трябва да се свържете днес или по-рано:\n\n");
        for (OrderRow row : due) {
            CustomerOrder o = row.order();
            String name = o.customer().name() == null ? "(без име)" : o.customer().name();
            b.append("• ").append(WHEN.format(o.fields().nextContactAt())).append(" – ").append(name);
            if (o.customer().phone() != null) {
                b.append(", ").append(o.customer().phone());
            }
            if (o.car().title() != null) {
                b.append(" – ").append(o.car().title());
            } else if (row.candidateCount() > 0) {
                b.append(" – предложени коли: ").append(row.candidateCount());
            }
            b.append("\n  ").append(siteUrl).append("/admin/orders/").append(o.id()).append('\n');
        }
        b.append("\nСписъкът е и в админ панела: ").append(siteUrl).append("/admin/orders?followUp=1\n");
        return b.toString();
    }

    private void send(String subject, String text) {
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("from", from);
        mail.put("to", List.of(to.split("\\s*,\\s*")));
        mail.put("subject", subject);
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
                log.warn("Follow-up e-mail: Resend answered HTTP {}: {}", r.statusCode(), r.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Follow-up e-mail not sent: {}", e.toString());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
