package com.premiumcars.auth;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Session login for the admin panel. The browser keeps only an HttpOnly session cookie (no password); changing
 * requests carry the CSRF token from the XSRF-TOKEN cookie in the X-XSRF-TOKEN header.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 200) String newPassword) {
    }

    /** @param mustChangePassword the user logged in with a one-time password and must choose an own one first */
    public record Me(String username, boolean mustChangePassword) {
    }

    private final AdminAuthService auth;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokens;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public AdminAuthController(AdminAuthService auth, SecurityContextRepository contextRepository,
                               CsrfTokenRepository csrfTokens) {
        this.auth = auth;
        this.contextRepository = contextRepository;
        this.csrfTokens = csrfTokens;
    }

    /** Sets the XSRF-TOKEN cookie (the login form calls it first). */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                    HttpServletResponse httpResponse) {
        AdminUser user = auth.authenticate(request.username(), request.password());

        // New session id after login (session fixation), then store the authentication in it
        HttpSession existing = httpRequest.getSession(false);
        if (existing != null) {
            httpRequest.changeSessionId();
        } else {
            httpRequest.getSession(true);
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user.username(), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);
        AdminSessionFilter.remember(httpRequest.getSession(), user.passwordChangedAt());

        // New CSRF token for the logged-in session (overwrites the cookie: one Set-Cookie, no delete first)
        csrfTokens.saveToken(csrfTokens.generateToken(httpRequest), httpRequest, httpResponse);
        return new Me(user.username(), user.mustChangePassword());
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
        csrfTokens.saveToken(null, httpRequest, httpResponse);
        return Map.of("loggedOut", true);
    }

    @GetMapping("/me")
    public Me me(Principal principal) {
        return new Me(principal.getName(), auth.find(principal.getName()).map(AdminUser::mustChangePassword).orElse(false));
    }

    @PostMapping("/password")
    public Map<String, Boolean> changePassword(@Valid @RequestBody PasswordChangeRequest request, Principal principal,
                                               HttpServletRequest httpRequest) {
        auth.changePassword(principal.getName(), request.currentPassword(), request.newPassword());
        // this session stays logged in; the user's other sessions end (AdminSessionFilter)
        auth.find(principal.getName())
                .ifPresent(u -> AdminSessionFilter.remember(httpRequest.getSession(), u.passwordChangedAt()));
        return Map.of("changed", true);
    }

    @ExceptionHandler(AdminAuthService.LoginFailedException.class)
    ProblemDetail loginFailed(AdminAuthService.LoginFailedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }
}
