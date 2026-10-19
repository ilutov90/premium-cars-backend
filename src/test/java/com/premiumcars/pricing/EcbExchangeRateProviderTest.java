package com.premiumcars.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;
import com.sun.net.httpserver.HttpServer;

/** The ECB daily reference rates, from a local copy of the ECB file (no network). */
class EcbExchangeRateProviderTest {

    /** Same structure as https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml (rates shortened). */
    private static final String ECB_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <gesmes:Sender><gesmes:name>European Central Bank</gesmes:name></gesmes:Sender>
              <Cube>
                <Cube time='%s'>
                  <Cube currency='USD' rate='1.1650'/>
                  <Cube currency='KRW' rate='1625.40'/>
                  <Cube currency='CNY' rate='8.3010'/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    private HttpServer server;
    private volatile String body = ECB_XML.formatted("2026-10-19");
    private volatile int status = 200;
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private EcbExchangeRateProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/eurofxref-daily.xml", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        var props = new AppProperties(null, null, null,
                new AppProperties.Pricing(Map.of("USD", new BigDecimal("0.86"), "AED", new BigDecimal("0.23"))),
                null, null);
        provider = new EcbExchangeRateProvider(props, events::add, true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/eurofxref-daily.xml"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void usesTheConfiguredRatesUntilTheEcbAnswers() {
        assertThat(provider.current().source()).isEqualTo("CONFIG");
        assertThat(provider.eurPerUnit("usd")).isEqualByComparingTo("0.86");
        assertThat(provider.eurPerUnit("EUR")).isEqualByComparingTo("1");
    }

    @Test
    void takesTheEcbRatesAndRepricesOncePerDay() {
        assertThat(provider.refresh()).isTrue();

        assertThat(provider.current().source()).isEqualTo("ECB");
        assertThat(provider.current().date()).isEqualTo(LocalDate.of(2026, 10, 19));
        // 1 EUR = 1.1650 USD -> 1 USD = 0.8583690987 EUR
        assertThat(provider.eurPerUnit("USD")).isEqualByComparingTo("0.8583690987");
        assertThat(provider.eurPerUnit("KRW")).isEqualByComparingTo("0.0006152331734");
        assertThat(provider.eurPerUnit("CNY")).isEqualByComparingTo("0.1204674136");
        // not published by the ECB: the configured rate stays
        assertThat(provider.eurPerUnit("AED")).isEqualByComparingTo("0.23");
        assertThat(events).containsExactly(new EcbExchangeRateProvider.ExchangeRatesUpdated(LocalDate.of(2026, 10, 19)));

        // the same day again: nothing to do
        assertThat(provider.refresh()).isFalse();
        assertThat(events).hasSize(1);

        body = ECB_XML.formatted("2026-10-20");
        assertThat(provider.refresh()).isTrue();
        assertThat(events).hasSize(2);
    }

    @Test
    void anUnreachableOrBrokenEcbKeepsTheRatesInUse() {
        status = 503;
        assertThat(provider.refresh()).isFalse();
        status = 200;
        body = "<html>maintenance</html>";
        assertThat(provider.refresh()).isFalse();

        assertThat(provider.current().source()).isEqualTo("CONFIG");
        assertThat(provider.eurPerUnit("USD")).isEqualByComparingTo("0.86");
        assertThat(events).isEmpty();
    }

    @Test
    void rejectsXmlWithADoctype() {
        byte[] xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
                <Cube><Cube time='2026-10-19'><Cube currency='USD' rate='&e;'/></Cube></Cube>
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EcbExchangeRateProvider.parse(xxe, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownCurrencyIsAClearError() {
        assertThatThrownBy(() -> provider.eurPerUnit("XYZ"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("XYZ");
    }
}
