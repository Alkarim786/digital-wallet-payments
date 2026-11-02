package io.github.alkarim786.wallet.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Calls fraud-service over HTTP. If it is slow, down or returns garbage the transfer is sent to manual REVIEW
 * (fail-safe, not fail-open and not fail-closed): money neither moves unchecked nor is the user hard-blocked.
 */
@Component
public class HttpFraudGate implements FraudGate {
    private static final Logger log = LoggerFactory.getLogger(HttpFraudGate.class);

    private record Hit(String rule, int score, String reason) {}

    private record Response(String decision, int score, List<Hit> hits) {}

    private final RestClient client;

    public HttpFraudGate(WalletProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.fraudTimeout());
        factory.setReadTimeout(props.fraudTimeout());
        this.client = RestClient.builder().baseUrl(props.fraudUrl()).requestFactory(factory).build();
    }

    /** The first call to a cold JVM can exceed the timeout; warm the path in the background so users never pay for it. */
    @EventListener(ApplicationReadyEvent.class)
    void warmUp() {
        Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 5; i++) {
                try {
                    client.post().uri("/fraud/check").body(new Input(0, 1, false, 0, null)).retrieve().toBodilessEntity();
                    return;
                } catch (Exception e) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
    }

    @Override
    public Verdict check(Input in) {
        try {
            Response r = client.post().uri("/fraud/check").body(in).retrieve().body(Response.class);
            if (r == null || r.decision() == null) {
                throw new IllegalStateException("empty fraud response");
            }
            List<String> reasons = r.hits() == null ? List.of() : r.hits().stream().map(h -> h.rule() + ": " + h.reason()).toList();
            return new Verdict(Decision.valueOf(r.decision()), r.score(), reasons);
        } catch (Exception e) {
            log.warn("fraud-service unavailable ({}); holding transfer for review", e.toString());
            return new Verdict(Decision.REVIEW, 0, List.of("FRAUD_UNAVAILABLE: risk check could not be completed, held for manual review"));
        }
    }
}
