package io.github.alkarim786.wallet.wallet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alkarim786.wallet.common.TransferEvent;
import io.github.alkarim786.wallet.wallet.FraudGate.Decision;
import io.github.alkarim786.wallet.wallet.Model.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The money-moving core.
 *
 * <p>One transfer is ONE database transaction that (1) claims the idempotency key, (2) locks both accounts with
 * SELECT ... FOR UPDATE in ascending id order (so two opposite transfers can never deadlock), (3) re-checks funds
 * under the lock, (4) writes a DEBIT and a CREDIT entry, (5) updates the cached balances and (6) writes the outbox
 * event. Either all of it commits or none of it does.
 *
 * <p>The fraud call happens BEFORE the transaction: holding row locks across a network call would serialise every
 * transfer touching a hot account behind a remote service.
 */
@Service
public class TransferService {

    private final LedgerRepository repo;
    private final FraudGate fraud;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final WalletProperties props;

    public TransferService(LedgerRepository repo, FraudGate fraud, TransactionTemplate tx, ObjectMapper json, WalletProperties props) {
        this.repo = repo;
        this.fraud = fraud;
        this.tx = tx;
        this.json = json;
        this.props = props;
    }

    // ---------------------------------------------------------------------------------- transfers

    public Outcome transfer(long userId, TransferCommand cmd, String idempotencyKey, String country) {
        validateKey(idempotencyKey);
        if (cmd.amountMinor() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "amount must be positive");
        }
        String hash = hash("TRANSFER", cmd.toAccountId(), cmd.amountMinor(), cmd.category(), cmd.note());

        var replay = replayIfKnown(idempotencyKey, userId, hash);
        if (replay != null) {
            return replay;
        }

        Account from = repo.ensureUserAccount(userId);
        Account to = repo.findAccount(cmd.toAccountId())
                .filter(a -> a.id() != Model.SYSTEM_ACCOUNT_ID)
                .orElseThrow(() -> ApiException.notFound("PAYEE_NOT_FOUND", "no wallet with account number " + cmd.toAccountId()));
        if (from.id() == to.id()) {
            throw ApiException.badRequest("SELF_TRANSFER", "cannot send money to your own account");
        }

        var verdict = fraud.check(new FraudGate.Input(userId, cmd.amountMinor(), !repo.hasCompletedTransfer(from.id(), to.id()),
                repo.countRecentAttempts(userId, 60), country));

        return tx.execute(status -> {
            if (!repo.claimKey(idempotencyKey, userId, hash)) {
                return replayClaimed(idempotencyKey, userId, hash);
            }
            UUID id = UUID.randomUUID();
            String txnStatus;
            switch (verdict.decision()) {
                case DECLINE -> txnStatus = "DECLINED";
                case REVIEW -> txnStatus = "HELD";
                default -> txnStatus = "COMPLETED";
            }
            // Lock BEFORE inserting the transactions row: that insert takes FOR KEY SHARE locks on both accounts
            // (foreign keys), and two transactions holding key-share locks that then both ask for FOR UPDATE deadlock.
            LockedPair pair = verdict.decision() == Decision.APPROVE ? lockPair(from.id(), to.id(), cmd.amountMinor(), true) : null;
            repo.insertTxn(new Txn(id, "TRANSFER", txnStatus, from.id(), to.id(), userId, cmd.amountMinor(), Model.USD,
                    clean(cmd.category(), 40), clean(cmd.note(), 140), verdict.score(), String.join("; ", verdict.reasons())));
            long balance;
            if (verdict.decision() == Decision.APPROVE) {
                balance = postEntries(id, pair, cmd.amountMinor(), true);
            } else {
                balance = repo.findAccount(from.id()).orElseThrow().balanceMinor();
                // A hold does not reserve funds, but holding a transfer the sender can never afford is pointless.
                if (verdict.decision() == Decision.REVIEW && balance < cmd.amountMinor()) {
                    throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "balance " + balance + " is less than " + cmd.amountMinor());
                }
            }
            publish(id, switch (txnStatus) {
                case "COMPLETED" -> "TRANSFER_COMPLETED";
                case "HELD" -> "TRANSFER_HELD";
                default -> "TRANSFER_DECLINED";
            }, userId, repo.ownerOf(to.id()).orElse(null), cmd.amountMinor(), String.join("; ", verdict.reasons()));
            var result = new TransferResult(id, txnStatus, cmd.amountMinor(), from.id(), to.id(), verdict.score(), verdict.reasons(), balance);
            int http = txnStatus.equals("DECLINED") ? HttpStatus.UNPROCESSABLE_ENTITY.value() : HttpStatus.CREATED.value();
            return store(idempotencyKey, userId, new StoredResponse(http, result));
        });
    }

    public Outcome topUp(long userId, long amountMinor, String idempotencyKey) {
        validateKey(idempotencyKey);
        if (amountMinor <= 0 || amountMinor > props.topupMaxMinor()) {
            throw ApiException.badRequest("INVALID_AMOUNT", "top-up must be between 1 and " + props.topupMaxMinor() + " minor units");
        }
        String hash = hash("TOPUP", amountMinor);
        var replay = replayIfKnown(idempotencyKey, userId, hash);
        if (replay != null) {
            return replay;
        }
        Account account = repo.ensureUserAccount(userId);
        return tx.execute(status -> {
            if (!repo.claimKey(idempotencyKey, userId, hash)) {
                return replayClaimed(idempotencyKey, userId, hash);
            }
            UUID id = UUID.randomUUID();
            LockedPair pair = lockPair(Model.SYSTEM_ACCOUNT_ID, account.id(), amountMinor, false);
            repo.insertTxn(new Txn(id, "TOPUP", "COMPLETED", Model.SYSTEM_ACCOUNT_ID, account.id(), userId, amountMinor, Model.USD,
                    "top-up", null, 0, null));
            long balance = postEntries(id, pair, amountMinor, false);
            publish(id, "TOPUP_COMPLETED", null, userId, amountMinor, null);
            return store(idempotencyKey, userId, new StoredResponse(HttpStatus.CREATED.value(),
                    new TransferResult(id, "COMPLETED", amountMinor, Model.SYSTEM_ACCOUNT_ID, account.id(), 0, List.of(), balance)));
        });
    }

    // -------------------------------------------------------------------------------- admin review

    public TransferResult approve(UUID txnId) {
        return tx.execute(status -> {
            Txn t = repo.lockTxn(txnId).orElseThrow(() -> ApiException.notFound("TXN_NOT_FOUND", "unknown transaction"));
            requireHeld(t);
            long balance = postEntries(t.id(), lockPair(t.fromAccount(), t.toAccount(), t.amountMinor(), true), t.amountMinor(), true);
            repo.setStatus(t.id(), "COMPLETED");
            publish(t.id(), "TRANSFER_RELEASED", t.initiatorUser(), repo.ownerOf(t.toAccount()).orElse(null), t.amountMinor(), "approved by admin");
            return new TransferResult(t.id(), "COMPLETED", t.amountMinor(), t.fromAccount(), t.toAccount(), t.fraudScore(), List.of(), balance);
        });
    }

    public TransferResult reject(UUID txnId) {
        return tx.execute(status -> {
            Txn t = repo.lockTxn(txnId).orElseThrow(() -> ApiException.notFound("TXN_NOT_FOUND", "unknown transaction"));
            requireHeld(t);
            repo.setStatus(t.id(), "REJECTED");
            publish(t.id(), "TRANSFER_REJECTED", t.initiatorUser(), repo.ownerOf(t.toAccount()).orElse(null), t.amountMinor(), "rejected by admin");
            long balance = repo.findAccount(t.fromAccount()).orElseThrow().balanceMinor();
            return new TransferResult(t.id(), "REJECTED", t.amountMinor(), t.fromAccount(), t.toAccount(), t.fraudScore(), List.of(), balance);
        });
    }

    private void requireHeld(Txn t) {
        if (!t.status().equals("HELD")) {
            throw ApiException.conflict("NOT_HELD", "transaction is " + t.status() + ", only HELD transactions can be reviewed");
        }
    }

    // ------------------------------------------------------------------------------- ledger posting

    record LockedPair(Account from, Account to) {}

    /**
     * Row-locks both accounts (SELECT ... FOR UPDATE) in ASCENDING id order regardless of transfer direction, so two
     * opposite transfers between the same accounts queue up instead of deadlocking, and checks funds under the lock.
     * MUST run inside a transaction and BEFORE any insert that references the accounts.
     */
    LockedPair lockPair(long fromId, long toId, long amount, boolean enforceFunds) {
        Account a = repo.lock(Math.min(fromId, toId));
        Account b = repo.lock(Math.max(fromId, toId));
        Account from = a.id() == fromId ? a : b;
        Account to = a.id() == toId ? a : b;
        if (enforceFunds && from.balanceMinor() < amount) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "balance " + from.balanceMinor() + " is less than " + amount);
        }
        return new LockedPair(from, to);
    }

    /** Writes the DEBIT + CREDIT entries and moves the cached balances. Returns the sender's new balance (top-up: the receiver's). */
    long postEntries(UUID txnId, LockedPair p, long amount, boolean senderView) {
        repo.insertEntry(txnId, p.from().id(), "DEBIT", amount, Model.USD);
        repo.insertEntry(txnId, p.to().id(), "CREDIT", amount, Model.USD);
        repo.addToBalance(p.from().id(), -amount);
        repo.addToBalance(p.to().id(), amount);
        return senderView ? p.from().balanceMinor() - amount : p.to().balanceMinor() + amount;
    }

    // -------------------------------------------------------------------------------- idempotency

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "send a unique Idempotency-Key header (max 100 chars)");
        }
    }

    /** Fast path before any work: a completed key replays its stored response without calling the fraud service. */
    private Outcome replayIfKnown(String key, long userId, String hash) {
        return repo.findKey(key, userId).map(k -> {
            checkHash(k.requestHash(), hash);
            return new Outcome(parse(k.status(), k.body()), true);
        }).orElse(null);
    }

    /** The insert lost the race (or it is a retry): the winner has committed by now, so its response is readable. */
    private Outcome replayClaimed(String key, long userId, String hash) {
        var k = repo.loadKey(key, userId);
        checkHash(k.requestHash(), hash);
        return new Outcome(parse(k.status(), k.body()), true);
    }

    private void checkHash(String stored, String current) {
        if (!stored.equals(current)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "this Idempotency-Key was already used with a different request body");
        }
    }

    private Outcome store(String key, long userId, StoredResponse r) {
        try {
            repo.completeKey(key, userId, r.status(), json.writeValueAsString(r.body()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return new Outcome(r, false);
    }

    private StoredResponse parse(int status, String body) {
        try {
            return new StoredResponse(status, json.readValue(body, TransferResult.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt stored idempotent response", e);
        }
    }

    static String hash(Object... parts) {
        try {
            var sb = new StringBuilder();
            for (Object p : parts) {
                sb.append(p == null ? "" : p).append('\u001f');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------- events

    private void publish(UUID txnId, String type, Long fromUser, Long toUser, long amount, String reason) {
        var event = new TransferEvent(UUID.randomUUID().toString(), txnId.toString(), type, fromUser, toUser, amount, Model.USD, reason, Instant.now());
        try {
            repo.addOutbox(TransferEvent.TOPIC, txnId.toString(), json.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
