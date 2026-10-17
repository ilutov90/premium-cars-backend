package com.premiumcars.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-client-IP limits on the endpoints worth abusing: admin login (password guessing) and the public inquiry
 * form (spam). In memory, fixed windows: enough for one instance. The client IP comes from X-Forwarded-For of
 * trusted proxies (server.forward-headers-strategy=native).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    record Rule(String method, String path, int limit, Duration window) {
    }

    private record Window(long start, int count) {
    }

    static final List<Rule> RULES = List.of(
            new Rule("POST", "/api/admin/login", 10, Duration.ofMinutes(5)),
            new Rule("POST", "/api/public/inquiries", 5, Duration.ofMinutes(10)));

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final boolean enabled;

    public RateLimitFilter(Clock clock, @Value("${app.rate-limit.enabled:true}") boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Rule rule : RULES) {
            if (rule.method().equals(request.getMethod()) && rule.path().equals(request.getRequestURI())) {
                if (!allow(rule, request.getRemoteAddr())) {
                    response.setStatus(429);
                    response.setHeader("Retry-After", String.valueOf(rule.window().toSeconds()));
                    response.setContentType("application/problem+json");
                    response.getWriter().write("{\"status\":429,\"detail\":\"Too many requests; try again later\"}");
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }

    boolean allow(Rule rule, String clientIp) {
        long now = clock.millis();
        long windowMs = rule.window().toMillis();
        if (windows.size() > 10_000) {
            windows.values().removeIf(w -> now - w.start() >= windowMs * 2);
        }
        Window w = windows.compute(rule.path() + "|" + clientIp, (k, old) ->
                old == null || now - old.start() >= windowMs ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        return w.count() <= rule.limit();
    }
}
