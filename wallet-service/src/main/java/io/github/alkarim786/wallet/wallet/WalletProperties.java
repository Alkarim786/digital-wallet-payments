package io.github.alkarim786.wallet.wallet;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("wallet")
public record WalletProperties(String fraudUrl, Duration fraudTimeout, Long topupMaxMinor, Outbox outbox) {

    public record Outbox(Boolean enabled, Duration pollInterval, Integer batchSize) {
        public Outbox {
            enabled = enabled == null || enabled;
            pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
            batchSize = batchSize == null ? 100 : batchSize;
        }
    }

    public WalletProperties {
        fraudUrl = fraudUrl == null ? "http://localhost:8083" : fraudUrl;
        fraudTimeout = fraudTimeout == null ? Duration.ofSeconds(2) : fraudTimeout;
        topupMaxMinor = topupMaxMinor == null ? 1_000_000L : topupMaxMinor;
        outbox = outbox == null ? new Outbox(null, null, null) : outbox;
    }
}
