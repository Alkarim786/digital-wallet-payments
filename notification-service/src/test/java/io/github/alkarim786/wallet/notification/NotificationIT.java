package io.github.alkarim786.wallet.notification;

import io.github.alkarim786.wallet.common.TransferEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Real Kafka + real Postgres: at-least-once delivery must still produce exactly one notification per user. */
@Tag("integration")
@SpringBootTest
class NotificationIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        PG.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired NotificationStore store;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    private void awaitCount(long expectedForUser, long userId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (store.forUser(userId, 50).size() >= expectedForUser) return;
            Thread.sleep(200);
        }
        fail("timed out waiting for notifications of user " + userId);
    }

    @Test
    void duplicateDeliveryCreatesOneNotificationPerUser() throws Exception {
        var event = new TransferEvent("evt-dup-1", "txn-1", "TRANSFER_COMPLETED", 501L, 502L, 2_500, "USD", null, Instant.now());
        String payload = json.writeValueAsString(event);
        for (int i = 0; i < 3; i++) {
            kafka.send(new ProducerRecord<>(TransferEvent.TOPIC, "txn-1", payload)).get();
        }
        awaitCount(1, 501);
        awaitCount(1, 502);
        Thread.sleep(1500); // give redeliveries time to be (incorrectly) applied
        assertEquals(1, store.forUser(501, 50).size());
        assertEquals(1, store.forUser(502, 50).size());
        assertTrue(store.forUser(502, 50).get(0).message().contains("$25.00"));
    }

    @Test
    void poisonMessageDoesNotBlockTheConsumer() throws Exception {
        kafka.send(new ProducerRecord<>(TransferEvent.TOPIC, "bad", "{not json")).get();
        var ok = new TransferEvent("evt-after-poison", "txn-2", "TOPUP_COMPLETED", null, 503L, 1_000, "USD", null, Instant.now());
        kafka.send(new ProducerRecord<>(TransferEvent.TOPIC, "txn-2", json.writeValueAsString(ok))).get();
        awaitCount(1, 503);
        assertEquals("TOPUP", store.forUser(503, 5).get(0).kind());
    }
}
