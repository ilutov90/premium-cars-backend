package com.premiumcars.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

/**
 * Admin login: bcrypt password hashes in {@code admin_user}, a lock after {@link #MAX_FAILED_ATTEMPTS} wrong
 * passwords, and the first admin created from ADMIN_INITIAL_PASSWORD (Vault) when there is none.
 */
@Service
public class AdminAuthService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final int MIN_PASSWORD_LENGTH = 12;
    static final String INITIAL_USERNAME = "admin";

    /** Thrown for any failed login; the message never says whether the user exists. */
    public static class LoginFailedException extends RuntimeException {
        LoginFailedException(String message) {
            super(message);
        }
    }

    private final AdminUserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final Clock clock;
    /** Compared against when the user does not exist, so both cases take the same time. */
    private final String dummyHash;

    public AdminAuthService(AdminUserRepository users, PasswordEncoder encoder, AppProperties props, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /** @return the user when the password is right and the user is not locked */
    public AdminUser authenticate(String username, String password) {
        Instant now = clock.instant();
        var found = users.findByUsername(username == null ? "" : username.trim());
        if (found.isEmpty()) {
            encoder.matches(password, dummyHash);
            throw new LoginFailedException("Wrong username or password");
        }
        AdminUser user = found.get();
        if (user.lockedAt(now)) {
            throw new LoginFailedException("Too many wrong passwords; try again after " + LOCK_DURATION.toMinutes() + " minutes");
        }
        if (!encoder.matches(password, user.passwordHash())) {
            users.recordFailure(user.id(), MAX_FAILED_ATTEMPTS, now.plus(LOCK_DURATION));
            log.warn("Failed admin login for '{}' ({} of {})", user.username(), user.failedAttempts() + 1, MAX_FAILED_ATTEMPTS);
            throw new LoginFailedException("Wrong username or password");
        }
        users.recordSuccess(user.id());
        log.info("Admin '{}' logged in", user.username());
        return user;
    }

    public void changePassword(String username, String currentPassword, String newPassword) {
        AdminUser user = users.findByUsername(username)
                .orElseThrow(() -> new BadRequestException("Unknown user"));
        if (!encoder.matches(currentPassword, user.passwordHash())) {
            throw new BadRequestException("The current password is wrong");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("The new password needs at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (newPassword.equals(currentPassword)) {
            throw new BadRequestException("The new password must differ from the current one");
        }
        users.updatePassword(user.id(), encoder.encode(newPassword));
        log.info("Admin '{}' changed the password", username);
    }

    /** Creates the first admin at startup when there is none. */
    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String initial = props.admin().initialPassword();
        if (initial == null || initial.isBlank()) {
            log.warn("No admin user and no ADMIN_INITIAL_PASSWORD in Vault: the admin panel cannot be used yet");
            return;
        }
        users.insert(INITIAL_USERNAME, encoder.encode(initial));
        log.info("Created admin user '{}' from ADMIN_INITIAL_PASSWORD; change the password after the first login",
                INITIAL_USERNAME);
    }
}
