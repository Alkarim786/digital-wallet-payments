package io.github.alkarim786.wallet.wallet;

import java.util.List;

/** Synchronous pre-transfer risk check. */
public interface FraudGate {

    record Input(long userId, long amountMinor, boolean newPayee, int transfersLastMinute, String country) {}

    enum Decision { APPROVE, REVIEW, DECLINE }

    record Verdict(Decision decision, int score, List<String> reasons) {
        public static Verdict approve() {
            return new Verdict(Decision.APPROVE, 0, List.of());
        }
    }

    Verdict check(Input input);
}
