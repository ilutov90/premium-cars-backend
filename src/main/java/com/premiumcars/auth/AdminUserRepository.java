package com.premiumcars.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code admin_user}. */
@Repository
public class AdminUserRepository {

    private static final String COLUMNS =
            "id, username, password_hash, failed_attempts, locked_until, last_login_at, password_changed_at";

    private final JdbcClient jdbc;

    public AdminUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM admin_user").query(Long.class).single();
    }

    public Optional<AdminUser> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM admin_user WHERE username = :username")
                .param("username", username)
                .query(AdminUserRepository::map)
                .optional();
    }

    public void insert(String username, String passwordHash) {
        jdbc.sql("INSERT INTO admin_user (username, password_hash) VALUES (:username, :hash)")
                .param("username", username)
                .param("hash", passwordHash)
                .update();
    }

    /** Counts a wrong password; locks the user until {@code lockUntil} when the count reaches {@code maxAttempts}. */
    public void recordFailure(long id, int maxAttempts, Instant lockUntil) {
        jdbc.sql("""
                        UPDATE admin_user SET
                            failed_attempts = failed_attempts + 1,
                            locked_until = CASE WHEN failed_attempts + 1 >= :max THEN :lockUntil ELSE locked_until END
                        WHERE id = :id""")
                .param("max", maxAttempts)
                .param("lockUntil", OffsetDateTime.ofInstant(lockUntil, ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    public void recordSuccess(long id) {
        jdbc.sql("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL, last_login_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("UPDATE admin_user SET password_hash = :hash, password_changed_at = now() WHERE id = :id")
                .param("hash", passwordHash)
                .param("id", id)
                .update();
    }

    private static AdminUser map(ResultSet rs, int row) throws SQLException {
        return new AdminUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getInt("failed_attempts"), instant(rs, "locked_until"), instant(rs, "last_login_at"),
                instant(rs, "password_changed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
