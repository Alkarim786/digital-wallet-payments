package io.github.alkarim786.wallet.fraud;

import io.github.alkarim786.wallet.fraud.FraudModel.Decision;
import io.github.alkarim786.wallet.fraud.FraudModel.Request;
import io.github.alkarim786.wallet.fraud.FraudModel.Result;
import io.github.alkarim786.wallet.fraud.FraudModel.RuleHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Additive rule engine. Each rule that fires contributes a score and a human-readable reason; the sum
 * (capped at 100) maps to APPROVE / REVIEW / DECLINE. The reasons are shown to admins reviewing held
 * transactions, so every decision is explainable.
 */
@Component
public class FraudEngine {

    private final FraudProperties p;

    public FraudEngine(FraudProperties props) {
        this.p = props;
    }

    public Result evaluate(Request r) {
        List<RuleHit> hits = new ArrayList<>();
        if (r.transfersLastMinute() >= p.maxTransfersPerMinute()) {
            hits.add(new RuleHit("VELOCITY", 40,
                    r.transfersLastMinute() + " transfers in the last minute (limit " + p.maxTransfersPerMinute() + ")"));
        }
        if (r.amountMinor() >= p.largeAmountMinor()) {
            hits.add(new RuleHit("LARGE_AMOUNT", 50, "amount " + fmt(r.amountMinor()) + " is at or above " + fmt(p.largeAmountMinor())));
        }
        if (r.newPayee() && r.amountMinor() >= p.newPayeeAmountMinor()) {
            hits.add(new RuleHit("NEW_PAYEE_LARGE", 40, "first payment to this payee and amount " + fmt(r.amountMinor())));
        }
        if (r.country() != null && p.blockedCountries().contains(r.country().toUpperCase(Locale.ROOT))) {
            hits.add(new RuleHit("BLOCKED_COUNTRY", 100, "request originates from blocked country " + r.country().toUpperCase(Locale.ROOT)));
        }
        int score = Math.min(100, hits.stream().mapToInt(RuleHit::score).sum());
        Decision d = score >= p.declineScore() ? Decision.DECLINE : score >= p.reviewScore() ? Decision.REVIEW : Decision.APPROVE;
        return new Result(score, d, List.copyOf(hits));
    }

    private static String fmt(long minor) {
        return String.format("%d.%02d", minor / 100, minor % 100);
    }
}
