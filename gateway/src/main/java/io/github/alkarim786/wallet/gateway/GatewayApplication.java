package io.github.alkarim786.wallet.gateway;

import io.github.alkarim786.wallet.common.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

import java.time.Duration;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    JwtService jwtService(@Value("${wallet.jwt.secret}") String secret) {
        // The gateway only verifies tokens; the TTL argument is irrelevant here.
        return new JwtService(secret, Duration.ofHours(1));
    }

    /** One bucket per user once authenticated (JwtAuthFilter runs first), else one per client address. */
    @Bean
    KeyResolver principalKeyResolver() {
        return exchange -> {
            String user = exchange.getRequest().getHeaders().getFirst(io.github.alkarim786.wallet.common.Headers.USER_ID);
            if (user != null) {
                return Mono.just("user:" + user);
            }
            var addr = exchange.getRequest().getRemoteAddress();
            return Mono.just("ip:" + (addr == null ? "unknown" : addr.getAddress().getHostAddress()));
        };
    }
}
