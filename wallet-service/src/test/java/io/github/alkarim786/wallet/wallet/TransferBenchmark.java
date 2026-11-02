package io.github.alkarim786.wallet.wallet;

import io.github.alkarim786.wallet.wallet.Model.Account;
import io.github.alkarim786.wallet.wallet.Model.TransferCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throughput / latency of the ledger on real PostgreSQL.
 *
 *   mvn -pl wallet-service -am test -Pbench -Dtest=TransferBenchmark -Dsurefire.failIfNoSpecifiedTests=false
 *
 * Calls TransferService directly (no HTTP, no gateway, fraud stubbed to APPROVE, Kafka relay off), so it measures the
 * database transaction: idempotency claim + two row locks + 2 entries + 2 balance updates + outbox insert + commit.
 * Postgres runs in Docker Desktop (WSL2) on the same machine as the load generator.
 */
@Tag("benchmark")
@SpringBootTest(properties = {"wallet.outbox.enabled=false", "spring.datasource.hikari.maximum-pool-size=80"})
class TransferBenchmark extends PostgresTestBase {

    static final int ACCOUNTS = 500;
    static final long INITIAL = 1_000_000; // = the per-request top-up cap, i.e. $10,000.00
    static final int SECONDS = Integer.getInteger("bench.seconds", 15);

    record Run(String scenario, int concurrency, long completed, long insufficient, double perSecond, double p50ms, double p95ms, double p99ms) {}

    private Run run(String scenario, int concurrency, List<Account> accounts, boolean hot) throws Exception {
        var pool = Executors.newFixedThreadPool(concurrency);
        var stop = System.nanoTime() + SECONDS * 1_000_000_000L;
        var ok = new AtomicLong();
        var poor = new AtomicLong();
        List<Future<long[]>> workers = new ArrayList<>();
        for (int w = 0; w < concurrency; w++) {
            final int seed = w;
            workers.add(pool.submit(() -> {
                var rnd = new Random(seed * 7919L + concurrency);
                long[] lat = new long[2_000_000 / concurrency];
                int n = 0;
                while (System.nanoTime() < stop && n < lat.length) {
                    Account from = accounts.get(rnd.nextInt(accounts.size()));
                    Account to = hot ? accounts.get(0) : accounts.get(rnd.nextInt(accounts.size()));
                    if (from.id() == to.id()) continue;
                    long t0 = System.nanoTime();
                    try {
                        transfers.transfer(from.ownerUserId(), new TransferCommand(to.id(), 1 + rnd.nextInt(500), "bench", null),
                                UUID.randomUUID().toString(), null);
                        ok.incrementAndGet();
                    } catch (ApiException e) {
                        poor.incrementAndGet();
                    }
                    lat[n++] = System.nanoTime() - t0;
                }
                return Arrays.copyOf(lat, n);
            }));
        }
        long started = System.nanoTime();
        List<Long> all = new ArrayList<>();
        for (var f : workers) for (long v : f.get(SECONDS + 60, TimeUnit.SECONDS)) all.add(v);
        double elapsed = (System.nanoTime() - started) / 1e9;
        pool.shutdown();
        Collections.sort(all);
        long completed = ok.get();
        return new Run(scenario, concurrency, completed, poor.get(), completed / Math.max(elapsed, SECONDS),
                pct(all, 50), pct(all, 95), pct(all, 99));
    }

    private static double pct(List<Long> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.round(p / 100.0 * (sorted.size() - 1)))) / 1e6;
    }

    @Test
    void benchmark() throws Exception {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) accounts.add(newUser(INITIAL));
        long totalBefore = accounts.stream().mapToLong(this::balance).sum();

        // warm up the JIT, connection pool and Postgres buffers
        run("warmup", 8, accounts, false);

        List<Run> runs = new ArrayList<>();
        for (int c : new int[]{1, 16, 64}) {
            runs.add(run("uniform random pairs over " + ACCOUNTS + " accounts", c, accounts, false));
        }
        for (int c : new int[]{16, 64}) {
            runs.add(run("hot account: every transfer credits ONE account", c, accounts, true));
        }

        var rec = queries.reconcile();
        long totalAfter = accounts.stream().mapToLong(this::balance).sum();
        assertTrue(rec.healthy(), "ledger must reconcile after the benchmark: " + rec);
        assertEquals(totalBefore, totalAfter, "money conserved across all benchmark accounts");

        var sb = new StringBuilder("{\n  \"seconds_per_run\": " + SECONDS + ",\n  \"accounts\": " + ACCOUNTS + ",\n  \"runs\": [\n");
        for (int i = 0; i < runs.size(); i++) {
            var r = runs.get(i);
            sb.append(String.format(Locale.ROOT,
                    "    {\"scenario\": \"%s\", \"concurrency\": %d, \"completed\": %d, \"insufficient_funds\": %d, \"transfers_per_s\": %.1f, \"p50_ms\": %.2f, \"p95_ms\": %.2f, \"p99_ms\": %.2f}%s\n",
                    r.scenario(), r.concurrency(), r.completed(), r.insufficient(), r.perSecond(), r.p50ms(), r.p95ms(), r.p99ms(),
                    i + 1 < runs.size() ? "," : ""));
        }
        sb.append(String.format(Locale.ROOT, "  ],\n  \"conservation\": {\"total_before_minor\": %d, \"total_after_minor\": %d, \"sum_of_all_balances\": %d, \"accounts_with_drift\": %d, \"unbalanced_transactions\": %d, \"healthy\": %s}\n}\n",
                totalBefore, totalAfter, rec.sumOfAllBalances(), rec.accountsWithDrift(), rec.unbalancedTransactions(), rec.healthy()));
        Path out = Path.of(System.getProperty("bench.out", "target/benchmark.json"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
    }
}
