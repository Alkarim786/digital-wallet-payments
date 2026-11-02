package io.github.alkarim786.wallet.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/** Thresholds are amounts in minor units (cents). Null means "use the default". */
@ConfigurationProperties("fraud")
public record FraudProperties(
        Integer maxTransfersPerMinute,
        Long largeAmountMinor,
        Long newPayeeAmountMinor,
        Set<String> blockedCountries,
        Integer reviewScore,
        Integer declineScore) {

    public FraudProperties {
        maxTransfersPerMinute = maxTransfersPerMinute == null ? 5 : maxTransfersPerMinute;
        largeAmountMinor = largeAmountMinor == null ? 500_000L : largeAmountMinor;
        newPayeeAmountMinor = newPayeeAmountMinor == null ? 100_000L : newPayeeAmountMinor;
        blockedCountries = blockedCountries == null ? Set.of("KP", "IR", "SY") : blockedCountries;
        reviewScore = reviewScore == null ? 40 : reviewScore;
        declineScore = declineScore == null ? 80 : declineScore;
    }
}
