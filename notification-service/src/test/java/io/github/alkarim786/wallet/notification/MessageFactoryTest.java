package io.github.alkarim786.wallet.notification;

import io.github.alkarim786.wallet.common.TransferEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MessageFactoryTest {

    private TransferEvent ev(String type, Long from, Long to, long amount) {
        return new TransferEvent("e1", "t1", type, from, to, amount, "USD", null, Instant.now());
    }

    @Test
    void completedTransferNotifiesBothSides() {
        var d = MessageFactory.draftsFor(ev("TRANSFER_COMPLETED", 1L, 2L, 12_345));
        assertEquals(2, d.size());
        assertEquals(1, d.get(0).userId());
        assertTrue(d.get(0).message().contains("$123.45"));
        assertEquals("RECEIVED", d.get(1).kind());
        assertEquals(2, d.get(1).userId());
    }

    @Test
    void heldDeclinedAndRejectedOnlyNotifyTheSender() {
        for (String t : new String[]{"TRANSFER_HELD", "TRANSFER_DECLINED", "TRANSFER_REJECTED"}) {
            var d = MessageFactory.draftsFor(ev(t, 1L, 2L, 100));
            assertEquals(1, d.size(), t);
            assertEquals(1, d.get(0).userId(), t);
        }
    }

    @Test
    void topUpNotifiesTheWalletOwnerOnly() {
        var d = MessageFactory.draftsFor(ev("TOPUP_COMPLETED", null, 7L, 5_000));
        assertEquals(1, d.size());
        assertEquals(7, d.get(0).userId());
        assertTrue(d.get(0).message().contains("$50.00"));
    }

    @Test
    void unknownEventTypesAreIgnored() {
        assertTrue(MessageFactory.draftsFor(ev("SOMETHING_NEW", 1L, 2L, 1)).isEmpty());
    }

    @Test
    void moneyFormattingKeepsLeadingZeroCents() {
        assertEquals("$0.05", MessageFactory.money(5));
        assertEquals("$10.00", MessageFactory.money(1000));
    }
}
