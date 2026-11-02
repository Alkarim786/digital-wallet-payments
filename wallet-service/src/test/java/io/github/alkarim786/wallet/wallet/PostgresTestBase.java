package io.github.alkarim786.wallet.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Real PostgreSQL (Testcontainers) with the Flyway migrations applied. The fraud service and Kafka relay are replaced
 * so these tests exercise exactly the ledger, locking and idempotency code that production runs.
 */
@SpringBootTest(properties = {"wallet.outbox.enabled=false", "spring.datasource.hikari.maximum-pool-size=40"})
@Import(PostgresTestBase.StubConfig.class)
abstract class PostgresTestBase {

    /** Set TEST_DB_URL (+ TEST_DB_USER/TEST_DB_PASSWORD) to run against an existing Postgres, e.g. inside a Docker network. */
    static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    static final PostgreSQLContainer<?> PG = EXTERNAL_URL == null ? new PostgreSQLContainer<>("postgres:16-alpine") : null;

    static {
        if (PG != null) {
            PG.start();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        if (PG == null) {
            r.add("spring.datasource.url", () -> EXTERNAL_URL);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "wallet"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "wallet"));
            return;
        }
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    static class StubFraudGate implements FraudGate {
        volatile Verdict next = Verdict.approve();
        volatile Input last;

        @Override
        public Verdict check(Input input) {
            last = input;
            return next;
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubFraudGate stubFraudGate() {
            return new StubFraudGate();
        }
    }

    private static final AtomicLong USER_IDS = new AtomicLong(10_000);

    @Autowired protected TransferService transfers;
    @Autowired protected QueryService queries;
    @Autowired protected LedgerRepository repo;
    @Autowired protected StubFraudGate fraud;
    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void approveByDefault() {
        fraud.next = FraudGate.Verdict.approve();
    }

    /** A fresh user with a wallet holding {@code balance} minor units (funded via a real top-up). */
    protected Model.Account newUser(long balance) {
        long userId = USER_IDS.incrementAndGet();
        var account = repo.ensureUserAccount(userId);
        if (balance > 0) {
            transfers.topUp(userId, balance, "fund-" + userId);
        }
        return repo.findAccount(account.id()).orElseThrow();
    }

    protected static long userOf(Model.Account a) {
        return a.ownerUserId();
    }

    protected long balance(Model.Account a) {
        return repo.findAccount(a.id()).orElseThrow().balanceMinor();
    }

    protected long entryCount(java.util.UUID txnId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE txn_id = :t").param("t", txnId).query(Long.class).single();
    }
}
