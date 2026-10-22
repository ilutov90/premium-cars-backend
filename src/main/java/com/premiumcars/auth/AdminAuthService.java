package com.premiumcars.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.config.AppProperties;

/**
 * Admin login: bcrypt password hashes in {@code admin_user}, a lock after {@link #MAX_FAILED_ATTEMPTS} wrong
 * passwords, and the first admin created from ADMIN_INITIAL_PASSWORD (Vault) when there is none. Admins create
 * and remove other admins (all with the same rights); a new or reset user gets a one-time password and must choose
 * an own one at the first login.
 */
@Service
public class AdminAuthService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final int MIN_PASSWORD_LENGTH = 12;
    static final String INITIAL_USERNAME = "admin";
    static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,49}");
    /** No look-alike characters (0/O, 1/l/I): the one-time password is read out or typed by hand. */
    private static final String ONE_TIME_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ONE_TIME_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A user created or reset by another admin, with the password to hand over (shown once, never stored). */
    public record OneTimePassword(AdminUser user, String password) {
    }

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

    public Optional<AdminUser> find(String username) {
        return users.findByUsername(username);
    }

    public List<AdminUser> list() {
        return users.findAll();
    }

    /** A new admin with a one-time password; the creator hands it over and the new admin replaces it. */
    public OneTimePassword create(String actor, String username) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new BadRequestException("Username: 3-50 characters, lowercase letters, digits, '.', '_' or '-'");
        }
        if (users.findByUsername(name).isPresent()) {
            throw new BadRequestException("The username is taken");
        }
        String password = oneTimePassword();
        long id = users.insert(name, encoder.encode(password), true);
        log.info("Admin '{}' created admin '{}'", actor, name);
        return new OneTimePassword(users.findById(id).orElseThrow(), password);
    }

    /** A new one-time password for another admin (forgotten password or locked user); their sessions end. */
    public OneTimePassword resetPassword(String actor, long id) {
        AdminUser user = users.findById(id).orElseThrow(() -> new NotFoundException("Admin user not found: " + id));
        if (user.username().equals(actor)) {
            throw new BadRequestException("Change your own password under Profile");
        }
        String password = oneTimePassword();
        users.resetPassword(id, encoder.encode(password));
        log.info("Admin '{}' reset the password of '{}'", actor, user.username());
        return new OneTimePassword(users.findById(id).orElseThrow(), password);
    }

    /** Removes another admin; their sessions end at their next request. The last admin cannot be removed. */
    public void delete(String actor, long id) {
        AdminUser user = users.findById(id).orElseThrow(() -> new NotFoundException("Admin user not found: " + id));
        if (user.username().equals(actor)) {
            throw new BadRequestException("You cannot delete yourself");
        }
        if (users.count() <= 1) {
            throw new BadRequestException("The last admin cannot be deleted");
        }
        users.delete(id);
        log.info("Admin '{}' deleted admin '{}'", actor, user.username());
    }

    static String oneTimePassword() {
        StringBuilder sb = new StringBuilder(ONE_TIME_LENGTH);
        for (int i = 0; i < ONE_TIME_LENGTH; i++) {
            sb.append(ONE_TIME_ALPHABET.charAt(RANDOM.nextInt(ONE_TIME_ALPHABET.length())));
        }
        return sb.toString();
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
