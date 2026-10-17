package com.premiumcars.config;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.premiumcars.auth.SpaCsrfTokenRequestHandler;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Admin panel behind a session login (see {@code auth} package): HttpOnly session cookie, CSRF token in the
 * XSRF-TOKEN cookie / X-XSRF-TOKEN header, /api/admin/** for logged-in admins only. Everything else is public and
 * read-only, except the public inquiry form (rate limited, no CSRF: it uses no login).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokens,
                                            SecurityContextRepository contextRepository) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/api/public/**"))
                .cors(Customizer.withDefaults())
                .securityContext(ctx -> ctx.securityContextRepository(contextRepository))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                // JSON instead of a redirect or a browser login popup
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "Login required"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403,
                                "Forbidden (missing or expired CSRF token: reload the page)")))
                .build();
    }

    private static void problem(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(AppProperties props) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse(); // the frontend reads it
        repository.setCookieCustomizer(c -> c.sameSite("Strict").secure(props.admin().secureCookies()).path("/"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Browsers reach the API through the frontend (same origin); cross-origin only for reading photos, if listed. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOrigins() == null ? List.of() : props.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET"));
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/media/**", config);
        return source;
    }
}
