package io.github.alkarim786.wallet.wallet;

import io.github.alkarim786.wallet.wallet.FraudGate.Decision;
import io.github.alkarim786.wallet.wallet.Model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class LedgerIT extends PostgresTestBase {

    @Autowired TransactionTemplate txTemplate;

    private Outcome send(Account from, Account to, long amount, String key) {
        return transfers.transfer(userOf(from), new TransferCommand(to.id(), amount, "food", "lunch"), key, "IN");
    }

    private void assertLedgerHealthy() {
        var r = queries.reconcile();
        assertTrue(r.healthy(), "reconciliation failed: " + r);
    }

    // ------------------------------------------------------------------------------ happy path

    @Test
    void transferWritesBalancedEntriesAndMovesExactlyTheAmount() {
        var alice = newUser(10_000);
        var bob = newUser(0);
        var out = send(alice, bob, 2_550, UUID.randomUUID().toString());
        assertEquals("COMPLETED", out.response().body().status());
        assertEquals(201, out.response().status());
        assertEquals(7_450, balance(alice));
        assertEquals(2_550, balance(bob));
        assertEquals(2, entryCount(out.response().body().txnId()));
        assertEquals(7_450, out.response().body().balanceMinor());
        assertLedgerHealthy();
    }

    @Test
    void insufficientFundsRollsBackEverythingAndIsRetryableWithSameKey() {
        var alice = newUser(1_000);
        var bob = newUser(0);
        var key = UUID.randomUUID().toString();
        var ex = assertThrows(ApiException.class, () -> send(alice, bob, 1_001, key));
        assertEquals("INSUFFICIENT_FUNDS", ex.code());
        assertEquals(1_000, balance(alice));
        assertEquals(0, balance(bob));
        // The failed attempt did not burn the key: after topping up, the same key works.
        transfers.topUp(userOf(alice), 500, "topup-" + key);
        assertEquals("COMPLETED", send(alice, bob, 1_001, key).response().body().status());
        assertLedgerHealthy();
    }

    @Test
    void validatesPayeeAndAmount() {
        var alice = newUser(1_000);
        assertEquals("PAYEE_NOT_FOUND", assertThrows(ApiException.class,
                () -> transfers.transfer(userOf(alice), new TransferCommand(999_999_999, 10, null, null), "k1", null)).code());
        assertEquals("PAYEE_NOT_FOUND", assertThrows(ApiException.class,
                () -> transfers.transfer(userOf(alice), new TransferCommand(Model.SYSTEM_ACCOUNT_ID, 10, null, null), "k2", null)).code());
        assertEquals("SELF_TRANSFER", assertThrows(ApiException.class, () -> send(alice, alice, 10, "k3")).code());
        var bob = newUser(0);
        assertEquals("INVALID_AMOUNT", assertThrows(ApiException.class, () -> send(alice, bob, 0, "k4")).code());
        assertEquals("INVALID_AMOUNT", assertThrows(ApiException.class, () -> send(alice, bob, -5, "k5")).code());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", assertThrows(ApiException.class, () -> send(alice, bob, 5, null)).code());
        assertEquals("INVALID_AMOUNT", assertThrows(ApiException.class, () -> transfers.topUp(userOf(alice), 2_000_000, "k6")).code());
    }

    // ------------------------------------------------------------------------------ idempotency

    @Test
    void sameKeyTwiceMovesMoneyOnce() {
        var alice = newUser(10_000);
        var bob = newUser(0);
        var key = UUID.randomUUID().toString();
        var first = send(alice, bob, 3_000, key);
        var second = send(alice, bob, 3_000, key);
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.response().body().txnId(), second.response().body().txnId());
        assertEquals(7_000, balance(alice));
        assertEquals(3_000, balance(bob));
    }

    @Test
    void sameKeyDifferentBodyIsRejected() {
        var alice = newUser(10_000);
        var bob = newUser(0);
        var key = UUID.randomUUID().toString();
        send(alice, bob, 3_000, key);
        var ex = assertThrows(ApiException.class, () -> send(alice, bob, 3_001, key));
        assertEquals("IDEMPOTENCY_KEY_REUSED", ex.code());
        assertEquals(422, ex.status().value());
        assertEquals(7_000, balance(alice));
    }

    @Test
    void keysAreScopedPerUser() {
        var alice = newUser(5_000);
        var carol = newUser(5_000);
        var bob = newUser(0);
        send(alice, bob, 100, "shared-key");
        assertFalse(send(carol, bob, 100, "shared-key").replayed());
        assertEquals(200, balance(bob));
    }

    @Test
    void sixteenConcurrentRetriesOfOneRequestCreateExactlyOneTransfer() throws Exception {
        var alice = newUser(10_000);
        var bob = newUser(0);
        var key = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(16);
        var start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return send(alice, bob, 1_000, key);
            }));
        }
        start.countDown();
        Set<UUID> ids = new HashSet<>();
        int fresh = 0;
        for (var f : futures) {
            var o = f.get(30, TimeUnit.SECONDS);
            ids.add(o.response().body().txnId());
            if (!o.replayed()) fresh++;
        }
        pool.shutdown();
        assertEquals(1, ids.size(), "all retries must return the same transaction");
        assertEquals(1, fresh);
        assertEquals(9_000, balance(alice));
        assertEquals(1_000, balance(bob));
        assertLedgerHealthy();
    }

    // ------------------------------------------------------------------------------ concurrency

    @Test
    void randomConcurrentTransfersConserveMoneyAndNeverDeadlock() throws Exception {
        int nUsers = 10;
        long initial = 50_000;
        List<Account> users = new ArrayList<>();
        for (int i = 0; i < nUsers; i++) users.add(newUser(initial));
        long before = users.stream().mapToLong(this::balance).sum();

        var pool = Executors.newFixedThreadPool(32);
        var ok = new AtomicInteger();
        var insufficient = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            final long seed = i;
            futures.add(pool.submit(() -> {
                var rnd = new Random(seed);
                var a = users.get(rnd.nextInt(nUsers));
                var b = users.get((users.indexOf(a) + 1 + rnd.nextInt(nUsers - 1)) % nUsers);
                try {
                    send(a, b, 1 + rnd.nextInt(30_000), UUID.randomUUID().toString());
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    assertEquals("INSUFFICIENT_FUNDS", e.code());
                    insufficient.incrementAndGet();
                }
            }));
        }
        for (var f : futures) f.get(120, TimeUnit.SECONDS); // a deadlock would time out here
        pool.shutdown();

        assertTrue(ok.get() > 100, "expected plenty of successful transfers, got " + ok);
        assertTrue(insufficient.get() > 0, "the test should also exercise the insufficient-funds path");
        assertEquals(before, users.stream().mapToLong(this::balance).sum(), "money was created or destroyed");
        for (var u : users) assertTrue(balance(u) >= 0, "negative balance");
        assertLedgerHealthy();
    }

    @Test
    void opposingTransfersBetweenTwoAccountsDoNotDeadlock() throws Exception {
        var a = newUser(1_000_000);
        var b = newUser(1_000_000);
        var pool = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            final boolean forward = i % 2 == 0;
            fs.add(pool.submit(() -> send(forward ? a : b, forward ? b : a, 10, UUID.randomUUID().toString())));
        }
        for (var f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(2_000_000, balance(a) + balance(b));
        assertEquals(1_000_000, balance(a)); // 200 each way, equal amounts
    }

    // ------------------------------------------------------------------------------ fraud flow

    @Test
    void declinedTransferIsRecordedButMovesNoMoney() {
        var alice = newUser(10_000);
        var bob = newUser(0);
        fraud.next = new FraudGate.Verdict(Decision.DECLINE, 100, List.of("BLOCKED_COUNTRY: nope"));
        var out = send(alice, bob, 1_000, UUID.randomUUID().toString());
        assertEquals(422, out.response().status());
        assertEquals("DECLINED", out.response().body().status());
        assertEquals(0, entryCount(out.response().body().txnId()));
        assertEquals(10_000, balance(alice));
        assertEquals(0, balance(bob));
        assertTrue(queries.flagged().stream().anyMatch(t -> t.id().equals(out.response().body().txnId())));
    }

    @Test
    void heldTransferCanBeApprovedOnceAndThenMovesMoney() {
        var alice = newUser(10_000);
        var bob = newUser(0);
        fraud.next = new FraudGate.Verdict(Decision.REVIEW, 50, List.of("LARGE_AMOUNT: big"));
        var out = send(alice, bob, 4_000, UUID.randomUUID().toString());
        var id = out.response().body().txnId();
        assertEquals("HELD", out.response().body().status());
        assertEquals(10_000, balance(alice), "held funds are not moved yet");

        var done = transfers.approve(id);
        assertEquals("COMPLETED", done.status());
        assertEquals(6_000, balance(alice));
        assertEquals(4_000, balance(bob));
        assertEquals(2, entryCount(id));
        assertEquals("NOT_HELD", assertThrows(ApiException.class, () -> transfers.approve(id)).code());
        assertEquals("NOT_HELD", assertThrows(ApiException.class, () -> transfers.reject(id)).code());
        assertLedgerHealthy();
    }

    @Test
    void aTransferTheSenderCannotAffordIsNotHeldEither() {
        var alice = newUser(1_000);
        var bob = newUser(0);
        fraud.next = new FraudGate.Verdict(Decision.REVIEW, 50, List.of("LARGE_AMOUNT: big"));
        assertEquals("INSUFFICIENT_FUNDS", assertThrows(ApiException.class, () -> send(alice, bob, 5_000, UUID.randomUUID().toString())).code());
        assertTrue(queries.flagged().stream().noneMatch(t -> t.amountMinor() == 5_000 && t.counterpartyAccount() == bob.id()));
    }

    @Test
    void rejectedHeldTransferNeverMovesMoney() {
        var alice = newUser(10_000);
        var bob = newUser(0);
        fraud.next = new FraudGate.Verdict(Decision.REVIEW, 50, List.of("x"));
        var id = send(alice, bob, 4_000, UUID.randomUUID().toString()).response().body().txnId();
        assertEquals("REJECTED", transfers.reject(id).status());
        assertEquals(10_000, balance(alice));
        assertEquals(0, entryCount(id));
    }

    @Test
    void approvingAHeldTransferFailsCleanlyIfFundsDisappeared() {
        var alice = newUser(5_000);
        var bob = newUser(0);
        var carol = newUser(0);
        fraud.next = new FraudGate.Verdict(Decision.REVIEW, 50, List.of("x"));
        var held = send(alice, bob, 4_000, UUID.randomUUID().toString()).response().body().txnId();
        fraud.next = FraudGate.Verdict.approve();
        send(alice, carol, 3_000, UUID.randomUUID().toString());
        assertEquals("INSUFFICIENT_FUNDS", assertThrows(ApiException.class, () -> transfers.approve(held)).code());
        assertEquals("HELD", jdbc.sql("SELECT status FROM transactions WHERE id = :i").param("i", held).query(String.class).single());
    }

    @Test
    void fraudFeaturesAreComputedFromHistory() {
        var alice = newUser(100_000);
        var bob = newUser(0);
        send(alice, bob, 100, UUID.randomUUID().toString());
        assertTrue(fraud.last.newPayee(), "first payment to bob");
        send(alice, bob, 100, UUID.randomUUID().toString());
        assertFalse(fraud.last.newPayee(), "bob has now been paid before");
        assertEquals(1, fraud.last.transfersLastMinute());
        assertEquals("IN", fraud.last.country());
    }

    // ------------------------------------------------------------------------------ DB invariants

    @Test
    void databaseRejectsAnUnbalancedLedgerTransactionAtCommit() {
        var alice = newUser(1_000);
        var bob = newUser(0);
        UUID id = UUID.randomUUID();
        var ex = assertThrows(RuntimeException.class, () -> txTemplate.executeWithoutResult(s -> {
            repo.insertTxn(new Txn(id, "TRANSFER", "COMPLETED", alice.id(), bob.id(), userOf(alice), 500, "USD", null, null, 0, null));
            repo.insertEntry(id, alice.id(), "DEBIT", 500, "USD");
            repo.insertEntry(id, bob.id(), "CREDIT", 400, "USD"); // 100 short
        }), "deferred constraint trigger must reject the unbalanced txn");
        assertTrue(String.valueOf(ex.getMessage()).contains("unbalanced") || String.valueOf(ex.getCause()).contains("unbalanced"), ex.toString());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM transactions WHERE id = :i").param("i", id).query(Long.class).single());
    }

    @Test
    void ledgerEntriesAreAppendOnly() {
        var alice = newUser(1_000);
        assertNotEquals(0L, jdbc.sql("SELECT count(*) FROM ledger_entries WHERE account_id = :a").param("a", alice.id()).query(Long.class).single());
        assertThrows(DataAccessException.class, () ->
                jdbc.sql("UPDATE ledger_entries SET amount_minor = 1 WHERE account_id = :a").param("a", alice.id()).update());
        assertThrows(DataAccessException.class, () ->
                jdbc.sql("DELETE FROM ledger_entries WHERE account_id = :a").param("a", alice.id()).update());
    }

    @Test
    void databaseForbidsNegativeUserBalanceEvenIfApplicationCodeWereWrong() {
        var alice = newUser(100);
        assertThrows(DataAccessException.class, () -> repo.addToBalance(alice.id(), -101));
    }

    // ------------------------------------------------------------------------------ queries

    @Test
    void historyFiltersAndPagination() {
        var alice = newUser(100_000);
        var bob = newUser(0);
        for (int i = 0; i < 25; i++) {
            transfers.transfer(userOf(alice), new TransferCommand(bob.id(), 100 + i, i % 2 == 0 ? "food" : "rent", null),
                    "hist-" + userOf(alice) + "-" + i, null);
        }
        var all = queries.history(userOf(alice), new HistoryFilter(null, null, null, null, null, null), 0, 10);
        assertEquals(26, all.total(), "25 transfers + the funding top-up");
        assertEquals(10, all.items().size());
        var last = queries.history(userOf(alice), new HistoryFilter(null, null, null, null, null, null), 2, 10);
        assertEquals(6, last.items().size());
        assertEquals(13, queries.history(userOf(alice), new HistoryFilter("SENT", null, null, "food", null, null), 0, 50).total());
        assertEquals(25, queries.history(userOf(bob), new HistoryFilter("RECEIVED", null, null, null, null, null), 0, 50).total());
        assertEquals(1, queries.history(userOf(alice), new HistoryFilter("TOPUP", null, null, null, null, null), 0, 50).total());
        assertEquals("RECEIVED", queries.history(userOf(bob), new HistoryFilter(null, null, null, null, null, null), 0, 1).items().get(0).direction());
        assertEquals(0, queries.history(userOf(bob), new HistoryFilter(null, "HELD", null, null, null, null), 0, 50).total());
    }

    @Test
    void recipientDoesNotSeeHeldOrDeclinedTransfers() {
        var alice = newUser(10_000);
        var bob = newUser(0);
        fraud.next = new FraudGate.Verdict(Decision.REVIEW, 50, List.of("x"));
        send(alice, bob, 100, UUID.randomUUID().toString());
        assertEquals(0, queries.history(userOf(bob), new HistoryFilter(null, null, null, null, null, null), 0, 50).total());
        assertEquals(2, queries.history(userOf(alice), new HistoryFilter(null, null, null, null, null, null), 0, 50).total());
    }

    @Test
    void analyticsTotalsMatchTransfers() {
        var alice = newUser(100_000);
        var bob = newUser(100_000);
        transfers.transfer(userOf(alice), new TransferCommand(bob.id(), 1_000, "food", null), "an-1-" + alice.id(), null);
        transfers.transfer(userOf(alice), new TransferCommand(bob.id(), 2_000, "rent", null), "an-2-" + alice.id(), null);
        transfers.transfer(userOf(bob), new TransferCommand(alice.id(), 500, "food", null), "an-3-" + alice.id(), null);
        var a = queries.analytics(userOf(alice), 7);
        assertEquals(7, a.byDay().size());
        assertEquals(3_000, a.totalSentMinor());
        assertEquals(500, a.totalReceivedMinor());
        assertEquals("rent", a.byCategory().get(0).category());
        assertEquals(2_000, a.byCategory().get(0).sentMinor());
    }
}
