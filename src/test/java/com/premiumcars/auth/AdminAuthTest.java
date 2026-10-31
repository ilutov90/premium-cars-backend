package com.premiumcars.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
        String limit = "{\"maxMileageKm\": 200000}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/admin/auto-import/max-mileage").session(b.session())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(limit))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/admin/auto-import/max-mileage").session(b.session())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(limit)
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

    private String json(org.springframework.test.web.servlet.ResultActions result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }

    @Test
    void adminsCreateAdminsWithAOneTimePassword() throws Exception {
        Browser admin = login("admin", "test-admin-password-1");

        // invalid and taken names are refused
        mvc.perform(post("/api/admin/users").session(admin.session())
                        .cookie(admin.xsrf()).header("X-XSRF-TOKEN", admin.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"a b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users").session(admin.session())
                        .cookie(admin.xsrf()).header("X-XSRF-TOKEN", admin.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\"}"))
                .andExpect(status().isBadRequest());

        var created = mvc.perform(post("/api/admin/users").session(admin.session())
                        .cookie(admin.xsrf()).header("X-XSRF-TOKEN", admin.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"Maria\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value("maria"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));
        String oneTime = json(created, "$.oneTimePassword");
        long id = Long.parseLong(json(created, "$.user.id"));
        assertThat(oneTime).hasSize(16);

        // the new admin may only change the password until it is done
        Browser maria = login("maria", oneTime);
        mvc.perform(get("/api/admin/me").session(maria.session()))
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/admin/cars").session(maria.session())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/password").session(maria.session())
                        .cookie(maria.xsrf()).header("X-XSRF-TOKEN", maria.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + oneTime + "\",\"newPassword\":\"marias-own-password\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/cars").session(maria.session())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/me").session(maria.session()))
                .andExpect(jsonPath("$.mustChangePassword").value(false));

        // a reset ends maria's session and gives a new one-time password
        var reset = mvc.perform(post("/api/admin/users/" + id + "/reset-password").session(admin.session())
                        .cookie(admin.xsrf()).header("X-XSRF-TOKEN", admin.xsrf().getValue()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/cars").session(maria.session())).andExpect(status().isUnauthorized());
        Browser again = login("maria", json(reset, "$.oneTimePassword"));

        // nobody deletes themselves; deleting maria ends her session
        long adminId = users.findByUsername("admin").orElseThrow().id();
        mvc.perform(delete("/api/admin/users/" + adminId).session(admin.session())
                        .cookie(admin.xsrf()).header("X-XSRF-TOKEN", admin.xsrf().getValue()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/users/" + id).session(admin.session())
                        .cookie(admin.xsrf()).header("X-XSRF-TOKEN", admin.xsrf().getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/me").session(again.session())).andExpect(status().isUnauthorized());
        assertThat(users.findByUsername("maria")).isEmpty();

        // the admin is still logged in
        mvc.perform(get("/api/admin/users").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'admin')].you").value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void ownPasswordChangeEndsOtherSessionsOnly() throws Exception {
        users.insert("twobrowsers", encoder.encode("first-password-123"));
        Browser laptop = login("twobrowsers", "first-password-123");
        Browser phone = login("twobrowsers", "first-password-123");
        mvc.perform(post("/api/admin/password").session(laptop.session())
                        .cookie(laptop.xsrf()).header("X-XSRF-TOKEN", laptop.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"first-password-123\",\"newPassword\":\"second-password-123\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/me").session(laptop.session())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/me").session(phone.session())).andExpect(status().isUnauthorized());
    }
}
