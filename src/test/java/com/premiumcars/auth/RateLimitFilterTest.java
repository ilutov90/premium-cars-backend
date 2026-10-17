package com.premiumcars.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    /** A clock the test moves forward. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-17T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final RateLimitFilter filter = new RateLimitFilter(clock, true);

    private int post(String path, String ip) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void inquiriesAreLimitedPerClientAndWindow() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/public/inquiries", "1.2.3.4")).isEqualTo(200);
        }
        assertThat(post("/api/public/inquiries", "1.2.3.4")).isEqualTo(429);
        assertThat(post("/api/public/inquiries", "5.6.7.8")).isEqualTo(200); // another client

        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(post("/api/public/inquiries", "1.2.3.4")).isEqualTo(200); // new window
    }

    @Test
    void loginIsLimitedAndOtherRequestsAreNot() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/api/admin/login", "9.9.9.9")).isEqualTo(200);
        }
        assertThat(post("/api/admin/login", "9.9.9.9")).isEqualTo(429);
        for (int i = 0; i < 50; i++) {
            assertThat(post("/api/admin/cars/1", "9.9.9.9")).isEqualTo(200);
        }
    }
}
