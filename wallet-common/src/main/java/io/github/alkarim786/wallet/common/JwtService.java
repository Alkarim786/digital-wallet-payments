package io.github.alkarim786.wallet.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/** Issues and verifies HS256 access tokens. Shared by auth-service (issue) and the gateway (verify). */
public final class JwtService {

    public record Identity(long userId, String email, Role role) {}

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(String secret, Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    public JwtService(String secret, Duration ttl, Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(Identity id) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(Long.toString(id.userId()))
                .claim("email", id.email())
                .claim("role", id.role().name())
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Empty when the token is malformed, tampered with, expired or carries an unknown role. */
    public Optional<Identity> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new Identity(Long.parseLong(c.getSubject()), c.get("email", String.class),
                    Role.valueOf(c.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration ttl() {
        return ttl;
    }
}
