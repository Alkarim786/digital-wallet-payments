package io.github.alkarim786.wallet.fraud;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class FraudModel {
    private FraudModel() {}

    /** Features computed by wallet-service; this service is stateless. */
    public record Request(
            long userId,
            @Positive long amountMinor,
            boolean newPayee,
            @Min(0) int transfersLastMinute,
            String country) {}

    public enum Decision { APPROVE, REVIEW, DECLINE }

    public record RuleHit(String rule, int score, String reason) {}

    public record Result(int score, Decision decision, List<RuleHit> hits) {}
}
