package com.premiumcars.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Checks every logged-in admin request against {@code admin_user}:
 * <ul>
 *   <li>the session ends when the user was deleted or the password changed since the login (another admin reset
 *       it, or the user changed it in another browser);</li>
 *   <li>while the user must replace a one-time password, only /me, /password, /logout and /csrf are allowed.</li>
 * </ul>
 * The login stores the user's {@code password_changed_at} in the session ({@link #PASSWORD_CHANGED_AT}). Not a
 * Spring bean on purpose: it runs only inside the security filter chain (see SecurityConfig).
 */
public class AdminSessionFilter extends OncePerRequestFilter {

    public static final String PASSWORD_CHANGED_AT = "premiumcars.passwordChangedAt";

    private static final Set<String> ALLOWED_WHILE_MUST_CHANGE =
            Set.of("/api/admin/me", "/api/admin/password", "/api/admin/logout", "/api/admin/csrf");

    private final AdminUserRepository users;

    public AdminSessionFilter(AdminUserRepository users) {
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken || session == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AdminUser> user = users.findByUsername(auth.getName());
        Object loggedInWith = session.getAttribute(PASSWORD_CHANGED_AT);
        if (user.isEmpty() || !Objects.equals(loggedInWith, user.get().passwordChangedAt())) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            problem(response, 401, "Session ended: log in again");
            return;
        }
        if (user.get().mustChangePassword() && !ALLOWED_WHILE_MUST_CHANGE.contains(request.getRequestURI())) {
            problem(response, 403, "Set your own password first");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Called after the login and after the user's own password change. */
    public static void remember(HttpSession session, Instant passwordChangedAt) {
        session.setAttribute(PASSWORD_CHANGED_AT, passwordChangedAt);
    }

    private static void problem(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
