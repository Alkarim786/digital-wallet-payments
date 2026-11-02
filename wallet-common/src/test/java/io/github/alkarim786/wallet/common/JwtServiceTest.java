package io.github.alkarim786.wallet.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void roundTrip() {
        var svc = new JwtService(SECRET, Duration.ofMinutes(5));
        var token = svc.issue(new JwtService.Identity(42, "a@b.com", Role.ADMIN));
        var id = svc.verify(token).orElseThrow();
        assertEquals(42, id.userId());
        assertEquals("a@b.com", id.email());
        assertEquals(Role.ADMIN, id.role());
    }

    @Test
    void rejectsTamperedAndWrongKeyAndGarbage() {
        var svc = new JwtService(SECRET, Duration.ofMinutes(5));
        var token = svc.issue(new JwtService.Identity(1, "a@b.com", Role.USER));
        assertTrue(svc.verify(token + "x").isEmpty());
        assertTrue(new JwtService("ffffffffffffffffffffffffffffffff", Duration.ofMinutes(5)).verify(token).isEmpty());
        assertTrue(svc.verify("not.a.jwt").isEmpty());
        assertTrue(svc.verify("").isEmpty());
    }

    @Test
    void rejectsExpired() {
        var past = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        var token = new JwtService(SECRET, Duration.ofMinutes(5), past).issue(new JwtService.Identity(1, "a@b.com", Role.USER));
        assertTrue(new JwtService(SECRET, Duration.ofMinutes(5)).verify(token).isEmpty());
    }

    @Test
    void shortSecretIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("short", Duration.ofMinutes(1)));
    }
}
