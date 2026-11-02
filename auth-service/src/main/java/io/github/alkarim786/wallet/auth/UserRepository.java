package io.github.alkarim786.wallet.auth;

import io.github.alkarim786.wallet.common.Role;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {

    public record User(long id, String email, String passwordHash, Role role) {}

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws DuplicateKeyException when the (case-insensitive) email is already registered. */
    public User insert(String email, String passwordHash, Role role) {
        long id = jdbc.sql("INSERT INTO users (email, password_hash, role) VALUES (:e, :p, :r) RETURNING id")
                .param("e", email).param("p", passwordHash).param("r", role.name())
                .query(Long.class).single();
        return new User(id, email, passwordHash, role);
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash, role FROM users WHERE lower(email) = lower(:e)")
                .param("e", email)
                .query((rs, n) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role"))))
                .optional();
    }

    public Optional<User> findById(long id) {
        return jdbc.sql("SELECT id, email, password_hash, role FROM users WHERE id = :i")
                .param("i", id)
                .query((rs, n) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role"))))
                .optional();
    }
}
