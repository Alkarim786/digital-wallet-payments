package io.github.alkarim786.wallet.fraud;

import io.github.alkarim786.wallet.fraud.FraudModel.Decision;
import io.github.alkarim786.wallet.fraud.FraudModel.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FraudEngineTest {
    private final FraudEngine engine = new FraudEngine(new FraudProperties(null, null, null, null, null, null));

    private Request req(long amount, boolean newPayee, int velocity, String country) {
        return new Request(1, amount, newPayee, velocity, country);
    }

    @Test
    void ordinaryTransferIsApproved() {
        var r = engine.evaluate(req(2_500, false, 0, "IN"));
        assertEquals(Decision.APPROVE, r.decision());
        assertEquals(0, r.score());
        assertTrue(r.hits().isEmpty());
    }

    @Test
    void velocityAloneGoesToReview() {
        var r = engine.evaluate(req(1_000, false, 5, null));
        assertEquals(Decision.REVIEW, r.decision());
        assertEquals("VELOCITY", r.hits().get(0).rule());
    }

    @Test
    void justUnderVelocityLimitIsFine() {
        assertEquals(Decision.APPROVE, engine.evaluate(req(1_000, false, 4, null)).decision());
    }

    @Test
    void largeAmountAloneGoesToReview() {
        var r = engine.evaluate(req(500_000, false, 0, null));
        assertEquals(Decision.REVIEW, r.decision());
        assertEquals(50, r.score());
        assertEquals(Decision.APPROVE, engine.evaluate(req(499_999, false, 0, null)).decision());
    }

    @Test
    void newPayeeWithLargeAmountIsReviewedButStacksWithLarge() {
        assertEquals(Decision.REVIEW, engine.evaluate(req(100_000, true, 0, null)).decision());
        var both = engine.evaluate(req(600_000, true, 0, null));
        assertEquals(90, both.score());
        assertEquals(Decision.DECLINE, both.decision());
        assertEquals(2, both.hits().size());
    }

    @Test
    void blockedCountryIsDeclinedCaseInsensitively() {
        var r = engine.evaluate(req(100, false, 0, "ir"));
        assertEquals(Decision.DECLINE, r.decision());
        assertEquals(100, r.score());
    }

    @Test
    void scoreIsCappedAt100AndReasonsAreExplained() {
        var r = engine.evaluate(req(900_000, true, 9, "KP"));
        assertEquals(100, r.score());
        assertEquals(4, r.hits().size());
        assertTrue(r.hits().stream().allMatch(h -> !h.reason().isBlank()));
    }
}
