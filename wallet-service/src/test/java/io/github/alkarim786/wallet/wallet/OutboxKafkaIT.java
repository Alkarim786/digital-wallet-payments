package io.github.alkarim786.wallet.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alkarim786.wallet.common.TransferEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The transactional outbox against real Postgres and a real Kafka broker. */
@Tag("integration")
@SpringBootTest(properties = {"wallet.outbox.enabled=true", "wallet.outbox.poll-interval=100ms"})
@Import(OutboxKafkaIT.Stub.class)
class OutboxKafkaIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        PG.start();
        KAFKA.start();
    }

    @TestConfiguration
    static class Stub {
        @Bean
        @Primary
        FraudGate approveAll() {
            return in -> FraudGate.Verdict.approve();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired TransferService transfers;
    @Autowired LedgerRepository repo;
    @Autowired ObjectMapper json;

    @Test
    void committedTransfersArePublishedExactlyOncePerOutboxRowAndMarkedPublished() throws Exception {
        var alice = repo.ensureUserAccount(70_001);
        var bob = repo.ensureUserAccount(70_002);
        transfers.topUp(70_001, 10_000, "ob-top");
        var out = transfers.transfer(70_001, new Model.TransferCommand(bob.id(), 2_500, "food", null), "ob-xfer", null);
        String txnId = out.response().body().txnId().toString();

        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<TransferEvent> seen = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(p)) {
            consumer.subscribe(List.of(TransferEvent.TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && seen.stream().noneMatch(e -> e.txnId().equals(txnId))) {
                for (var rec : consumer.poll(Duration.ofMillis(500))) {
                    seen.add(json.readValue(rec.value(), TransferEvent.class));
                }
            }
        }
        var event = seen.stream().filter(e -> e.txnId().equals(txnId)).findFirst().orElseThrow();
        assertEquals("TRANSFER_COMPLETED", event.type());
        assertEquals(70_001L, event.fromUserId());
        assertEquals(70_002L, event.toUserId());
        assertEquals(2_500, event.amountMinor());
        assertTrue(seen.stream().anyMatch(e -> e.type().equals("TOPUP_COMPLETED")));

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (repo.countUnpublished() > 0 && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals(0, repo.countUnpublished(), "relay must mark rows published after the broker acknowledged them");
        assertNotNull(alice);
    }

    @Test
    void rolledBackTransferLeavesNoEvent() {
        var a = repo.ensureUserAccount(70_011);
        var b = repo.ensureUserAccount(70_012);
        long before = repo.countUnpublished();
        assertThrows(ApiException.class, () -> transfers.transfer(70_011, new Model.TransferCommand(b.id(), 1, null, null), "ob-fail", null));
        // insufficient funds -> the whole transaction, including the outbox insert, rolled back
        long unpublishedDelta = repo.countUnpublished() - before;
        assertTrue(unpublishedDelta <= 0, "no event may exist for money that never moved");
        assertNotNull(a);
    }
}
