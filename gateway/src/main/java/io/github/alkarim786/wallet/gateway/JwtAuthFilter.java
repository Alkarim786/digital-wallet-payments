package io.github.alkarim786.wallet.gateway;

import io.github.alkarim786.wallet.common.Headers;
import io.github.alkarim786.wallet.common.JwtService;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Authentication at the edge. Verifies the bearer JWT once, then forwards the identity as trusted headers.
 * Any identity headers the client sent are stripped first, so a caller can never impersonate another user by
 * setting X-User-Id themselves. Internal services must not be reachable except through this gateway.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/auth/register");

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public int getOrder() {
        return -100; // before the rate limiter, so it can key on the verified user id
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        var cleaned = exchange.getRequest().mutate().headers(h -> {
            h.remove(Headers.USER_ID);
            h.remove(Headers.USER_ROLE);
            h.remove(Headers.USER_EMAIL);
        }).build();
        var clean = exchange.mutate().request(cleaned).build();

        if (PUBLIC_PATHS.contains(path)) {
            return chain.filter(clean);
        }

        String auth = clean.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return unauthorized(clean, "missing bearer token");
        }
        var identity = jwt.verify(auth.substring(7).trim());
        if (identity.isEmpty()) {
            return unauthorized(clean, "invalid or expired token");
        }
        var id = identity.get();
        var authed = clean.getRequest().mutate().headers(h -> {
            h.set(Headers.USER_ID, Long.toString(id.userId()));
            h.set(Headers.USER_ROLE, id.role().name());
            h.set(Headers.USER_EMAIL, id.email());
        }).build();
        return chain.filter(clean.mutate().request(authed).build());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        byte[] body = ("{\"error\":\"UNAUTHENTICATED\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
