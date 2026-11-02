package io.github.alkarim786.wallet.wallet;

import io.github.alkarim786.wallet.wallet.Model.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** All SQL lives here. Methods that must run inside the caller's database transaction say so. */
@Repository
public class LedgerRepository {

    private static final String ACCOUNT_COLS = "id, owner_user_id, type, currency, balance_minor";

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------------------------ accounts

    private static Account account(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long owner = rs.getLong("owner_user_id");
        return new Account(rs.getLong("id"), rs.wasNull() ? null : owner, rs.getString("type"), rs.getString("currency"),
                rs.getLong("balance_minor"));
    }

    /** Idempotent and safe under concurrent first requests. */
    public Account ensureUserAccount(long userId) {
        jdbc.sql("INSERT INTO accounts (owner_user_id, type) VALUES (:u, 'USER') ON CONFLICT (owner_user_id) DO NOTHING")
                .param("u", userId).update();
        return jdbc.sql("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE owner_user_id = :u")
                .param("u", userId).query(LedgerRepository::account).single();
    }

    public Optional<Account> findAccount(long id) {
        return jdbc.sql("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE id = :i").param("i", id)
                .query(LedgerRepository::account).optional();
    }

    /** Row-locks one account (SELECT ... FOR UPDATE) and returns its current state. Must be inside a transaction. */
    public Account lock(long id) {
        return jdbc.sql("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE id = :i FOR UPDATE").param("i", id)
                .query(LedgerRepository::account).optional()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "account " + id + " does not exist"));
    }

    public void addToBalance(long accountId, long delta) {
        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :d WHERE id = :i")
                .param("d", delta).param("i", accountId).update();
    }

    // --------------------------------------------------------------------------------- transactions

    public void insertTxn(Txn t) {
        jdbc.sql("""
                INSERT INTO transactions (id, type, status, from_account, to_account, initiator_user, amount_minor, currency,
                                          category, note, fraud_score, fraud_reasons)
                VALUES (:id, :type, :status, :from, :to, :init, :amt, :cur, :cat, :note, :score, :reasons)""")
                .param("id", t.id()).param("type", t.type()).param("status", t.status())
                .param("from", t.fromAccount()).param("to", t.toAccount()).param("init", t.initiatorUser())
                .param("amt", t.amountMinor()).param("cur", t.currency()).param("cat", t.category()).param("note", t.note())
                .param("score", t.fraudScore()).param("reasons", t.fraudReasons())
                .update();
    }

    public void insertEntry(UUID txnId, long accountId, String direction, long amount, String currency) {
        jdbc.sql("INSERT INTO ledger_entries (txn_id, account_id, direction, amount_minor, currency) VALUES (:t, :a, :d, :m, :c)")
                .param("t", txnId).param("a", accountId).param("d", direction).param("m", amount).param("c", currency).update();
    }

    public void setStatus(UUID txnId, String status) {
        jdbc.sql("UPDATE transactions SET status = :s WHERE id = :i").param("s", status).param("i", txnId).update();
    }

    /** Locks the transaction row; used so two admins cannot approve/reject the same held transfer. */
    public Optional<Txn> lockTxn(UUID id) {
        return jdbc.sql("""
                SELECT id, type, status, from_account, to_account, initiator_user, amount_minor, currency, category, note,
                       fraud_score, fraud_reasons FROM transactions WHERE id = :i FOR UPDATE""")
                .param("i", id).query(LedgerRepository::txn).optional();
    }

    private static Txn txn(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Txn(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("status"), rs.getLong("from_account"),
                rs.getLong("to_account"), rs.getLong("initiator_user"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("category"), rs.getString("note"), rs.getInt("fraud_score"), rs.getString("fraud_reasons"));
    }

    // ----------------------------------------------------------------------------- fraud features

    public int countRecentAttempts(long initiatorUser, int seconds) {
        return jdbc.sql("""
                SELECT count(*) FROM transactions
                WHERE initiator_user = :u AND type = 'TRANSFER' AND created_at > now() - make_interval(secs => :s)""")
                .param("u", initiatorUser).param("s", seconds).query(Integer.class).single();
    }

    public boolean hasCompletedTransfer(long from, long to) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM transactions WHERE from_account = :f AND to_account = :t AND status = 'COMPLETED' AND type = 'TRANSFER')")
                .param("f", from).param("t", to).query(Boolean.class).single();
    }

    // -------------------------------------------------------------------------------- idempotency

    /** INSERT ... ON CONFLICT DO NOTHING: a concurrent duplicate blocks here until the first request commits. */
    public boolean claimKey(String key, long userId, String requestHash) {
        return jdbc.sql("""
                INSERT INTO idempotency_keys (key, user_id, request_hash, response_status, response_body)
                VALUES (:k, :u, :h, 0, '') ON CONFLICT (key, user_id) DO NOTHING""")
                .param("k", key).param("u", userId).param("h", requestHash).update() == 1;
    }

    public record StoredKey(String requestHash, int status, String body) {}

    public StoredKey loadKey(String key, long userId) {
        return jdbc.sql("SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE key = :k AND user_id = :u")
                .param("k", key).param("u", userId)
                .query((rs, n) -> new StoredKey(rs.getString(1).trim(), rs.getInt(2), rs.getString(3))).single();
    }

    public Optional<StoredKey> findKey(String key, long userId) {
        return jdbc.sql("SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE key = :k AND user_id = :u AND response_status <> 0")
                .param("k", key).param("u", userId)
                .query((rs, n) -> new StoredKey(rs.getString(1).trim(), rs.getInt(2), rs.getString(3))).optional();
    }

    public void completeKey(String key, long userId, int status, String body) {
        jdbc.sql("UPDATE idempotency_keys SET response_status = :s, response_body = :b WHERE key = :k AND user_id = :u")
                .param("s", status).param("b", body).param("k", key).param("u", userId).update();
    }

    // ------------------------------------------------------------------------------------- outbox

    public void addOutbox(String topic, String key, String payload) {
        jdbc.sql("INSERT INTO outbox (topic, msg_key, payload) VALUES (:t, :k, :p)")
                .param("t", topic).param("k", key).param("p", payload).update();
    }

    public record OutboxRow(long id, String topic, String key, String payload) {}

    /** SKIP LOCKED lets several relay instances drain the table without blocking each other. */
    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.sql("SELECT id, topic, msg_key, payload FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT :l FOR UPDATE SKIP LOCKED")
                .param("l", limit).query((rs, n) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    public void markPublished(long id) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :i").param("i", id).update();
    }

    public long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    public Optional<Long> ownerOf(long accountId) {
        return jdbc.sql("SELECT owner_user_id FROM accounts WHERE id = :i AND owner_user_id IS NOT NULL").param("i", accountId)
                .query(Long.class).optional();
    }

    // ------------------------------------------------------------------------------------ queries

    public Page<TxnView> history(long accountId, HistoryFilter f, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE (t.from_account = :a OR t.to_account = :a)");
        Map<String, Object> p = new HashMap<>();
        p.put("a", accountId);
        // A user only sees ledger-moving rows on the receiving side; held/declined transfers are only shown to the sender.
        where.append(" AND (t.from_account = :a OR t.status = 'COMPLETED')");
        String dir = f.direction() == null ? "ALL" : f.direction().toUpperCase(Locale.ROOT);
        switch (dir) {
            case "SENT" -> where.append(" AND t.from_account = :a AND t.type = 'TRANSFER'");
            case "RECEIVED" -> where.append(" AND t.to_account = :a AND t.type = 'TRANSFER'");
            case "TOPUP" -> where.append(" AND t.type = 'TOPUP'");
            default -> { }
        }
        if (f.status() != null) { where.append(" AND t.status = :st"); p.put("st", f.status()); }
        if (f.type() != null) { where.append(" AND t.type = :ty"); p.put("ty", f.type()); }
        if (f.category() != null) { where.append(" AND t.category = :cat"); p.put("cat", f.category()); }
        if (f.from() != null) { where.append(" AND t.created_at >= :from"); p.put("from", Timestamp.from(f.from())); }
        if (f.to() != null) { where.append(" AND t.created_at < :to"); p.put("to", Timestamp.from(f.to())); }

        long total = jdbc.sql("SELECT count(*) FROM transactions t" + where).params(p).query(Long.class).single();
        p.put("lim", size);
        p.put("off", (long) page * size);
        List<TxnView> items = jdbc.sql("""
                SELECT t.id, t.type, t.status, t.from_account, t.to_account, t.amount_minor, t.currency, t.category, t.note,
                       t.fraud_score, t.fraud_reasons, t.created_at
                FROM transactions t""" + where + " ORDER BY t.created_at DESC, t.id LIMIT :lim OFFSET :off")
                .params(p)
                .query((rs, n) -> {
                    long from = rs.getLong("from_account");
                    long to = rs.getLong("to_account");
                    String type = rs.getString("type");
                    String direction = type.equals("TOPUP") ? "TOPUP" : from == accountId ? "SENT" : "RECEIVED";
                    return new TxnView(rs.getObject("id", UUID.class), type, rs.getString("status"), direction,
                            from == accountId ? to : from, rs.getLong("amount_minor"), rs.getString("currency"),
                            rs.getString("category"), rs.getString("note"), rs.getInt("fraud_score"), rs.getString("fraud_reasons"),
                            rs.getTimestamp("created_at").toInstant());
                }).list();
        return new Page<>(items, page, size, total);
    }

    public List<DayTotal> dailyTotals(long accountId, Instant since) {
        return jdbc.sql("""
                SELECT to_char(date_trunc('day', created_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS d,
                       COALESCE(SUM(amount_minor) FILTER (WHERE from_account = :a), 0) AS sent,
                       COALESCE(SUM(amount_minor) FILTER (WHERE to_account = :a), 0) AS received
                FROM transactions
                WHERE type = 'TRANSFER' AND status = 'COMPLETED' AND created_at >= :since AND (from_account = :a OR to_account = :a)
                GROUP BY 1 ORDER BY 1""")
                .param("a", accountId).param("since", Timestamp.from(since))
                .query((rs, n) -> new DayTotal(rs.getString("d"), rs.getLong("sent"), rs.getLong("received"))).list();
    }

    public List<CategoryTotal> categoryTotals(long accountId, Instant since) {
        return jdbc.sql("""
                SELECT COALESCE(category, 'uncategorized') AS c, SUM(amount_minor) AS s
                FROM transactions
                WHERE type = 'TRANSFER' AND status = 'COMPLETED' AND from_account = :a AND created_at >= :since
                GROUP BY 1 ORDER BY 2 DESC""")
                .param("a", accountId).param("since", Timestamp.from(since))
                .query((rs, n) -> new CategoryTotal(rs.getString("c"), rs.getLong("s"))).list();
    }

    public List<TxnView> flagged(int limit) {
        return jdbc.sql("""
                SELECT id, type, status, from_account, to_account, amount_minor, currency, category, note, fraud_score,
                       fraud_reasons, created_at FROM transactions WHERE status IN ('HELD', 'DECLINED', 'REJECTED')
                ORDER BY (status = 'HELD') DESC, created_at DESC LIMIT :l""")
                .param("l", limit)
                .query((rs, n) -> new TxnView(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("status"),
                        "SENT", rs.getLong("to_account"), rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getString("category"), rs.getString("note"), rs.getInt("fraud_score"), rs.getString("fraud_reasons"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** Independent audit: balances must sum to zero, equal their entries, and every txn must balance. */
    public Reconciliation reconcile() {
        long sum = jdbc.sql("SELECT COALESCE(SUM(balance_minor), 0) FROM accounts").query(Long.class).single();
        long drift = jdbc.sql("""
                SELECT count(*) FROM accounts a WHERE a.balance_minor <>
                  COALESCE((SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END)
                            FROM ledger_entries e WHERE e.account_id = a.id), 0)""").query(Long.class).single();
        long unbalanced = jdbc.sql("""
                SELECT count(*) FROM (SELECT txn_id FROM ledger_entries GROUP BY txn_id
                  HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) <> 0) x""")
                .query(Long.class).single();
        return new Reconciliation(sum, drift, unbalanced, sum == 0 && drift == 0 && unbalanced == 0);
    }
}
