package io.github.alkarim786.wallet.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alkarim786.wallet.common.TransferEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final ObjectMapper json;
    private final NotificationStore store;

    public EventConsumer(ObjectMapper json, NotificationStore store) {
        this.json = json;
        this.store = store;
    }

    @KafkaListener(topics = TransferEvent.TOPIC)
    public void onMessage(String payload) {
        TransferEvent event;
        try {
            event = json.readValue(payload, TransferEvent.class);
        } catch (JsonProcessingException e) {
            log.error("dropping unparseable event: {}", e.getOriginalMessage()); // a poison pill must not block the partition
            return;
        }
        handle(event);
    }

    /** Idempotent: redelivery of the same event writes nothing new. */
    void handle(TransferEvent event) {
        for (var d : MessageFactory.draftsFor(event)) {
            if (store.insert(event.eventId(), d.userId(), d.kind(), d.message(), event.txnId(), event.amountMinor())) {
                // The "email" channel is simulated: a production sender would call SES/SendGrid here.
                log.info("EMAIL to user {}: {}", d.userId(), d.message());
            }
        }
    }
}
