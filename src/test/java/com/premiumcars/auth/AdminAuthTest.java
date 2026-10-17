package com.premiumcars.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.servlet.http.Cookie;

/**
 * The admin login as the frontend uses it: GET /api/admin/csrf for the XSRF-TOKEN cookie, POST /api/admin/login,
 * then the session cookie plus the X-XSRF-TOKEN header on changing requests. Real PostgreSQL in Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AdminAuthTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    AdminUserRepository users;

    @Autowired
    PasswordEncoder encoder;

    /** A logged-in browser: session + CSRF cookie. */
    record Browser(MockHttpSession session, Cookie xsrf) {
    }

    private Cookie csrfCookie() throws Exception {
        Cookie cookie = mvc.perform(get("/api/admin/csrf")).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse(); // the frontend reads it
        return cookie;
    }

    private Browser login(String username, String password) throws Exception {
        Cookie xsrf = csrfCookie();
        var result = mvc.perform(post("/api/admin/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();
        Cookie fresh = result.getResponse().getCookie("XSRF-TOKEN"); // rotated at login
        assertThat(fresh).isNotNull();
        assertThat(fresh.getValue()).isNotEqualTo(xsrf.getValue());
        return new Browser((MockHttpSession) result.getRequest().getSession(false), fresh);
    }

    @Test
    void adminApiNeedsALogin() throws Exception {
        mvc.perform(get("/api/admin/cars")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/cars")).andExpect(status().isOk());
    }

    @Test
    void initialAdminLogsInWithASessionAndCsrfProtectsChanges() throws Exception {
        Browser b = login("admin", "test-admin-password-1"); // from app.admin.initial-password (Vault in real runs)

        mvc.perform(get("/api/admin/me").session(b.session())).andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(get("/api/admin/cars").session(b.session())).andExpect(status().isOk());

        // a changing request without the CSRF header is refused, with it allowed
        mvc.perform(post("/api/admin/cars/recalculate-prices").session(b.session()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/cars/recalculate-prices").session(b.session())
                        .cookie(b.xsrf()).header("X-XSRF-TOKEN", b.xsrf().getValue()))
                .andExpect(status().isOk());

        // logout ends the session
        mvc.perform(post("/api/admin/logout").session(b.session())
                        .cookie(b.xsrf()).header("X-XSRF-TOKEN", b.xsrf().getValue()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/me").session(b.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-password-1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void fiveWrongPasswordsLockTheUser() throws Exception {
        users.insert("locktest", encoder.encode("right-password-123"));
        for (int i = 0; i < AdminAuthService.MAX_FAILED_ATTEMPTS; i++) {
            Cookie xsrf = csrfCookie();
            mvc.perform(post("/api/admin/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"locktest\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Wrong username or password"));
        }
        Cookie xsrf = csrfCookie();
        mvc.perform(post("/api/admin/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"locktest\",\"password\":\"right-password-123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Too many wrong passwords")));
        assertThat(users.findByUsername("locktest").orElseThrow().lockedUntil()).isNotNull();
    }

    @Test
    void unknownUserGetsTheSameAnswer() throws Exception {
        Cookie xsrf = csrfCookie();
        mvc.perform(post("/api/admin/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong username or password"));
    }

    @Test
    void passwordChange() throws Exception {
        users.insert("changer", encoder.encode("old-password-123"));
        Browser b = login("changer", "old-password-123");

        mvc.perform(post("/api/admin/password").session(b.session())
                        .cookie(b.xsrf()).header("X-XSRF-TOKEN", b.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/password").session(b.session())
                        .cookie(b.xsrf()).header("X-XSRF-TOKEN", b.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"a-much-better-password\"}"))
                .andExpect(status().isOk());

        login("changer", "a-much-better-password");
        assertThat(encoder.matches("a-much-better-password",
                users.findByUsername("changer").orElseThrow().passwordHash())).isTrue();
    }
}
