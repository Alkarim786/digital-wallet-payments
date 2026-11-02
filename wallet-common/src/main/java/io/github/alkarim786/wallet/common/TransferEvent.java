package io.github.alkarim786.wallet.common;

import java.time.Instant;

/** Event published to the wallet.transactions topic through the transactional outbox. */
public record TransferEvent(
        String eventId,
        String txnId,
        String type,            // TRANSFER_COMPLETED | TRANSFER_HELD | TRANSFER_DECLINED | TRANSFER_RELEASED | TRANSFER_REJECTED | TOPUP_COMPLETED
        Long fromUserId,
        Long toUserId,
        long amountMinor,
        String currency,
        String reason,
        Instant occurredAt) {

    public static final String TOPIC = "wallet.transactions";
}
