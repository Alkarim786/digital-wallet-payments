package io.github.alkarim786.wallet.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class NotificationStore {

    public record Notification(long id, String kind, String message, String txnId, Long amountMinor, Instant createdAt, boolean read) {}

    private final JdbcClient jdbc;

    public NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if a row was inserted, false if this (event, user) pair was already processed. */
    public boolean insert(String eventId, long userId, String kind, String message, String txnId, long amountMinor) {
        return jdbc.sql("""
                INSERT INTO notifications (event_id, user_id, kind, message, txn_id, amount_minor)
                VALUES (:e, :u, :k, :m, :t, :a) ON CONFLICT (event_id, user_id) DO NOTHING""")
                .param("e", eventId).param("u", userId).param("k", kind).param("m", message).param("t", txnId).param("a", amountMinor)
                .update() == 1;
    }

    public List<Notification> forUser(long userId, int limit) {
        return jdbc.sql("SELECT id, kind, message, txn_id, amount_minor, created_at, read_at FROM notifications WHERE user_id = :u ORDER BY id DESC LIMIT :l")
                .param("u", userId).param("l", limit)
                .query((rs, n) -> new Notification(rs.getLong("id"), rs.getString("kind"), rs.getString("message"), rs.getString("txn_id"),
                        (Long) rs.getObject("amount_minor"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") != null))
                .list();
    }

    public int markAllRead(long userId) {
        return jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL").param("u", userId).update();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM notifications").query(Long.class).single();
    }
}
