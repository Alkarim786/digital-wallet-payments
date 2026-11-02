package io.github.alkarim786.wallet.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Transactional outbox relay. Events are inserted in the same database transaction as the ledger entries, so an event
 * exists if and only if the money moved. This poller then publishes them to Kafka at-least-once: a crash between the
 * send and the "published" update re-sends the row, which is why consumers deduplicate by eventId.
 */
@Component
@ConditionalOnProperty(name = "wallet.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final LedgerRepository repo;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final WalletProperties props;

    public OutboxRelay(LedgerRepository repo, KafkaTemplate<String, String> kafka, TransactionTemplate tx, WalletProperties props) {
        this.repo = repo;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.poll-interval:200ms}")
    public void relay() {
        try {
            int sent;
            do {
                sent = relayBatch();
            } while (sent >= props.outbox().batchSize());
        } catch (Exception e) {
            log.warn("outbox relay failed, will retry: {}", e.toString());
        }
    }

    /** Publishes one batch; rows sent before a failure are still marked, the rest are retried next tick. */
    int relayBatch() {
        Integer sent = tx.execute(status -> {
            var rows = repo.lockUnpublished(props.outbox().batchSize());
            int n = 0;
            for (var row : rows) {
                try {
                    kafka.send(row.topic(), row.key(), row.payload()).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("publish of outbox row {} failed: {}", row.id(), e.toString());
                    break;
                }
                repo.markPublished(row.id());
                n++;
            }
            return n;
        });
        return sent == null ? 0 : sent;
    }
}
