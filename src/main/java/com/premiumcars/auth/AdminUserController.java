package com.premiumcars.auth;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admins manage admins: list, create (one-time password), reset a password, delete. Same rights for everyone. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    public record AdminUserView(long id, String username, Instant createdAt, Instant lastLoginAt,
                                Instant passwordChangedAt, boolean mustChangePassword, boolean locked, boolean you) {
    }

    public record CreateRequest(@NotBlank @Size(max = 50) String username) {
    }

    /** The one-time password is in this answer only; it is never stored in clear. */
    public record OneTimePasswordView(AdminUserView user, String oneTimePassword) {
    }

    private final AdminAuthService auth;
    private final Clock clock;

    public AdminUserController(AdminAuthService auth, Clock clock) {
        this.auth = auth;
        this.clock = clock;
    }

    @GetMapping
    public List<AdminUserView> list(Principal principal) {
        return auth.list().stream().map(u -> view(u, principal)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OneTimePasswordView create(@Valid @RequestBody CreateRequest request, Principal principal) {
        var created = auth.create(principal.getName(), request.username());
        return new OneTimePasswordView(view(created.user(), principal), created.password());
    }

    @PostMapping("/{id}/reset-password")
    public OneTimePasswordView resetPassword(@PathVariable long id, Principal principal) {
        var reset = auth.resetPassword(principal.getName(), id);
        return new OneTimePasswordView(view(reset.user(), principal), reset.password());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, Principal principal) {
        auth.delete(principal.getName(), id);
    }

    private AdminUserView view(AdminUser u, Principal principal) {
        return new AdminUserView(u.id(), u.username(), u.createdAt(), u.lastLoginAt(), u.passwordChangedAt(),
                u.mustChangePassword(), u.lockedAt(clock.instant()), u.username().equals(principal.getName()));
    }
}
