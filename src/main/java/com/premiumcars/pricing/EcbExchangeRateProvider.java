package com.premiumcars.pricing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

/**
 * Exchange rates from the European Central Bank's daily reference rates (published on working days around
 * 16:00 CET), checked every hour. Until the first successful download, and whenever the ECB cannot be reached,
 * the rates from configuration ({@code app.pricing.exchange-rates}) are used. A new ECB day publishes
 * {@link ExchangeRatesUpdated}, which reprices every car.
 */
@Component
public class EcbExchangeRateProvider implements ExchangeRateProvider {

    /** The rates in use: EUR per one unit of each currency. */
    public record Rates(String source, LocalDate date, Map<String, BigDecimal> eurPerUnit) {
    }

    /** Published when the ECB rates of a new day are in use. */
    public record ExchangeRatesUpdated(LocalDate date) {
    }

    private static final Logger log = LoggerFactory.getLogger(EcbExchangeRateProvider.class);

    private final boolean enabled;
    private final URI url;
    private final ApplicationEventPublisher events;
    private final AtomicReference<Rates> current;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    public EcbExchangeRateProvider(AppProperties props, ApplicationEventPublisher events,
                                   @Value("${app.pricing.ecb.enabled:true}") boolean enabled,
                                   @Value("${app.pricing.ecb.url:https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml}")
                                   URI url) {
        this.enabled = enabled;
        this.url = url;
        this.events = events;
        Map<String, BigDecimal> configured = new TreeMap<>();
        if (props.pricing() != null && props.pricing().exchangeRates() != null) {
            props.pricing().exchangeRates().forEach((k, v) -> configured.put(k.toUpperCase(Locale.ROOT), v));
        }
        this.current = new AtomicReference<>(new Rates("CONFIG", null, Map.copyOf(configured)));
    }

    @Override
    public BigDecimal eurPerUnit(String currency) {
        String code = currency == null ? "EUR" : currency.toUpperCase(Locale.ROOT);
        if ("EUR".equals(code)) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = current.get().eurPerUnit().get(code);
        if (rate == null) {
            throw new BadRequestException("No exchange rate for currency " + code
                    + " (the ECB does not publish it and app.pricing.exchange-rates has none)");
        }
        return rate;
    }

    public Rates current() {
        return current.get();
    }

    /** First download right after the start (before that, the configured rates are in use). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void refreshOnStartup() {
        refresh();
    }

    /** Every hour; a new ECB day appears once per working day. */
    @Scheduled(cron = "${app.pricing.ecb.cron:0 15 * * * *}", zone = "Europe/Berlin")
    public void refreshHourly() {
        refresh();
    }

    /** @return true when rates of a new day were taken over */
    public boolean refresh() {
        if (!enabled) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<byte[]> r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                log.warn("ECB rates: HTTP {}; keeping the {} rates", r.statusCode(), current.get().source());
                return false;
            }
            Rates fresh = parse(r.body(), current.get().eurPerUnit());
            Rates old = current.get();
            if ("ECB".equals(old.source()) && fresh.date().equals(old.date())) {
                return false;
            }
            current.set(fresh);
            log.info("ECB rates of {} in use: {}", fresh.date(), fresh.eurPerUnit());
            events.publishEvent(new ExchangeRatesUpdated(fresh.date()));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            log.warn("ECB rates not available ({}); keeping the {} rates", e.toString(), current.get().source());
            return false;
        }
    }

    /**
     * The ECB file lists "units of currency per 1 EUR" ({@code <Cube currency="USD" rate="1.1650"/>}); the result is
     * EUR per unit. Currencies the ECB does not publish keep their configured rate.
     */
    static Rates parse(byte[] xml, Map<String, BigDecimal> fallback) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList cubes = doc.getElementsByTagNameNS("*", "Cube");
            LocalDate date = null;
            Map<String, BigDecimal> rates = new TreeMap<>(fallback);
            int found = 0;
            for (int i = 0; i < cubes.getLength(); i++) {
                Element cube = (Element) cubes.item(i);
                if (cube.hasAttribute("time")) {
                    date = LocalDate.parse(cube.getAttribute("time"));
                }
                if (cube.hasAttribute("currency") && cube.hasAttribute("rate")) {
                    BigDecimal perEur = new BigDecimal(cube.getAttribute("rate"));
                    if (perEur.signum() > 0) {
                        rates.put(cube.getAttribute("currency").toUpperCase(Locale.ROOT),
                                BigDecimal.ONE.divide(perEur, new MathContext(10, RoundingMode.HALF_UP)));
                        found++;
                    }
                }
            }
            if (date == null || found == 0) {
                throw new IllegalArgumentException("no rates in the ECB file");
            }
            return new Rates("ECB", date, Map.copyOf(rates));
        } catch (IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalArgumentException("unreadable ECB file: " + e.getMessage(), e);
        }
    }
}
