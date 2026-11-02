package io.github.alkarim786.wallet.gateway;

import io.github.alkarim786.wallet.common.JwtService;
import io.github.alkarim786.wallet.common.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthFilterTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(10));
    private final JwtAuthFilter filter = new JwtAuthFilter(jwt);

    private record Result(MockServerWebExchange exchange, ServerWebExchange forwarded) {}

    private Result run(MockServerHttpRequest.BaseBuilder<?> req) {
        var exchange = MockServerWebExchange.from(req);
        var forwarded = new AtomicReference<ServerWebExchange>();
        filter.filter(exchange, e -> {
            forwarded.set(e);
            return Mono.empty();
        }).block();
        return new Result(exchange, forwarded.get());
    }

    private String token(long id, Role role) {
        return jwt.issue(new JwtService.Identity(id, "u" + id + "@x.com", role));
    }

    @Test
    void validTokenIsForwardedWithIdentityHeaders() {
        var r = run(MockServerHttpRequest.get("/api/wallet/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(7, Role.ADMIN)));
        assertNotNull(r.forwarded());
        var h = r.forwarded().getRequest().getHeaders();
        assertEquals("7", h.getFirst("X-User-Id"));
        assertEquals("ADMIN", h.getFirst("X-User-Role"));
        assertEquals("u7@x.com", h.getFirst("X-User-Email"));
    }

    @Test
    void clientSuppliedIdentityHeadersCannotImpersonateAnotherUser() {
        var r = run(MockServerHttpRequest.get("/api/wallet/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(7, Role.USER))
                .header("X-User-Id", "1").header("X-User-Role", "ADMIN"));
        var h = r.forwarded().getRequest().getHeaders();
        assertEquals(java.util.List.of("7"), h.get("X-User-Id"));
        assertEquals(java.util.List.of("USER"), h.get("X-User-Role"));
    }

    @Test
    void spoofedHeadersAreStrippedOnPublicPathsToo() {
        var r = run(MockServerHttpRequest.post("/api/auth/login").header("X-User-Id", "1").header("X-User-Role", "ADMIN"));
        assertNotNull(r.forwarded());
        assertNull(r.forwarded().getRequest().getHeaders().getFirst("X-User-Id"));
        assertNull(r.forwarded().getRequest().getHeaders().getFirst("X-User-Role"));
    }

    @Test
    void missingMalformedTamperedAndExpiredTokensAreRejected() {
        var expired = new JwtService(SECRET, Duration.ofMinutes(1), Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC))
                .issue(new JwtService.Identity(1, "a@b.c", Role.USER));
        for (String auth : new String[]{null, "Basic abc", "Bearer", "Bearer garbage", "Bearer " + token(1, Role.USER) + "x", "Bearer " + expired}) {
            var req = MockServerHttpRequest.get("/api/wallet/account");
            if (auth != null) req.header(HttpHeaders.AUTHORIZATION, auth);
            var r = run(req);
            assertNull(r.forwarded(), "must not be forwarded: " + auth);
            assertEquals(HttpStatus.UNAUTHORIZED, r.exchange().getResponse().getStatusCode(), String.valueOf(auth));
        }
    }

    @Test
    void registerAndLoginArePublic() {
        assertNotNull(run(MockServerHttpRequest.post("/api/auth/register")).forwarded());
        assertNotNull(run(MockServerHttpRequest.post("/api/auth/login")).forwarded());
        // but /me is not
        assertNull(run(MockServerHttpRequest.get("/api/auth/me")).forwarded());
    }

    @Test
    void runsBeforeTheRateLimiter() {
        assertTrue(filter.getOrder() < 0);
    }
}
