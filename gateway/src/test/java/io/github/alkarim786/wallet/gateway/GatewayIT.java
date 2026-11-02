package io.github.alkarim786.wallet.gateway;

import com.sun.net.httpserver.HttpServer;
import io.github.alkarim786.wallet.common.JwtService;
import io.github.alkarim786.wallet.common.Role;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** The real gateway (routing, JWT filter, Redis token-bucket limiter) in front of a stub downstream service. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "wallet.jwt.secret=0123456789abcdef0123456789abcdef",
        "RATE_LIMIT_REPLENISH=1", "RATE_LIMIT_BURST=5"})
class GatewayIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    static HttpServer downstream;

    static {
        REDIS.start();
        try {
            downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Echoes the path and the identity headers it received, which is what a real service would see.
            downstream.createContext("/", ex -> {
                String body = ex.getRequestURI().getPath() + "|" + ex.getRequestHeaders().getFirst("X-User-Id")
                        + "|" + ex.getRequestHeaders().getFirst("X-User-Role");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            downstream.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        downstream.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String url = "http://127.0.0.1:" + downstream.getAddress().getPort();
        r.add("AUTH_URL", () -> url);
        r.add("WALLET_URL", () -> url);
        r.add("NOTIFICATIONS_URL", () -> url);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired WebTestClient client;

    private final JwtService jwt = new JwtService("0123456789abcdef0123456789abcdef", Duration.ofMinutes(5));

    private String bearer(long id, Role role) {
        return "Bearer " + jwt.issue(new JwtService.Identity(id, "u@x.com", role));
    }

    @Test
    void protectedRouteNeedsAValidToken() {
        client.get().uri("/api/wallet/account").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/wallet/account").header("Authorization", "Bearer nope").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void validTokenIsRoutedWithRewrittenPathAndVerifiedIdentity() {
        client.get().uri("/api/wallet/transactions?page=0").header("Authorization", bearer(42, Role.USER))
                .header("X-User-Id", "1") // spoof attempt
                .exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("/wallet/transactions|42|USER");
    }

    @Test
    void authRoutesArePublicAndRewritten() {
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("/auth/login|null|null");
    }

    @Test
    void notificationsRouteIsRewritten() {
        client.get().uri("/api/notifications").header("Authorization", bearer(9, Role.USER))
                .exchange().expectBody(String.class).isEqualTo("/notifications|9|USER");
    }

    @Test
    void redisTokenBucketReturns429AfterTheBurstAndIsPerUser() {
        int limited = 0;
        for (int i = 0; i < 20; i++) {
            var status = client.get().uri("/api/wallet/account").header("Authorization", bearer(777, Role.USER))
                    .exchange().returnResult(String.class).getStatus();
            if (status == HttpStatus.TOO_MANY_REQUESTS) limited++;
        }
        assertTrue(limited >= 10, "expected most of 20 rapid requests beyond a burst of 5 to be limited, got " + limited);
        // another user has their own bucket
        client.get().uri("/api/wallet/account").header("Authorization", bearer(778, Role.USER)).exchange().expectStatus().isOk();
    }
}
