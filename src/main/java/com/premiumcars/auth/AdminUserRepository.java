package com.premiumcars.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code admin_user}. */
@Repository
public class AdminUserRepository {

    private static final String COLUMNS =
            "id, username, password_hash, failed_attempts, locked_until, last_login_at, password_changed_at, "
                    + "must_change_password, created_at";

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

    public Optional<AdminUser> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM admin_user WHERE id = :id")
                .param("id", id)
                .query(AdminUserRepository::map)
                .optional();
    }

    public List<AdminUser> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM admin_user ORDER BY created_at, id")
                .query(AdminUserRepository::map)
                .list();
    }

    public void insert(String username, String passwordHash) {
        insert(username, passwordHash, false);
    }

    /** @return the new user's id */
    public long insert(String username, String passwordHash, boolean mustChangePassword) {
        return jdbc.sql("""
                        INSERT INTO admin_user (username, password_hash, must_change_password)
                        VALUES (:username, :hash, :mustChange)
                        RETURNING id""")
                .param("username", username)
                .param("hash", passwordHash)
                .param("mustChange", mustChangePassword)
                .query(Long.class)
                .single();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM admin_user WHERE id = :id").param("id", id).update() > 0;
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

    /** The user's own new password. */
    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("""
                        UPDATE admin_user SET password_hash = :hash, password_changed_at = clock_timestamp(),
                            must_change_password = false
                        WHERE id = :id""")
                .param("hash", passwordHash)
                .param("id", id)
                .update();
    }

    /** A one-time password set by another admin: unlocks the user, who must choose an own password next. */
    public void resetPassword(long id, String passwordHash) {
        jdbc.sql("""
                        UPDATE admin_user SET password_hash = :hash, password_changed_at = clock_timestamp(),
                            must_change_password = true, failed_attempts = 0, locked_until = NULL
                        WHERE id = :id""")
                .param("hash", passwordHash)
                .param("id", id)
                .update();
    }

    private static AdminUser map(ResultSet rs, int row) throws SQLException {
        return new AdminUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getInt("failed_attempts"), instant(rs, "locked_until"), instant(rs, "last_login_at"),
                instant(rs, "password_changed_at"), rs.getBoolean("must_change_password"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
