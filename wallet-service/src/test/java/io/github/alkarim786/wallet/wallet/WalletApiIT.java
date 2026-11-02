package io.github.alkarim786.wallet.wallet;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP layer: header-based identity, validation, status codes and admin authorisation. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletApiIT extends PostgresTestBase {

    @Autowired TestRestTemplate rest;

    private HttpHeaders headers(long userId, String role, String key) {
        var h = new HttpHeaders();
        h.add("X-User-Id", String.valueOf(userId));
        h.add("X-User-Role", role);
        if (key != null) h.add("Idempotency-Key", key);
        return h;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(String path, long userId, String role, String key, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(userId, role, key)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> get(String path, long userId, String role) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(userId, role, null)), Map.class);
    }

    @Test
    void topUpTransferAndReplayOverHttp() {
        long alice = 20_001, bob = 20_002;
        assertEquals(HttpStatus.CREATED, post("/wallet/topups", alice, "USER", "t-" + UUID.randomUUID(), Map.of("amountMinor", 50_000)).getStatusCode());
        var bobAccount = ((Number) get("/wallet/account", bob, "USER").getBody().get("accountId")).longValue();
        var key = "x-" + UUID.randomUUID();
        var body = Map.of("toAccountId", bobAccount, "amountMinor", 1_234, "category", "food", "note", "pizza");
        var first = post("/wallet/transfers", alice, "USER", key, body);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals("false", first.getHeaders().getFirst("Idempotent-Replay"));
        var again = post("/wallet/transfers", alice, "USER", key, body);
        assertEquals("true", again.getHeaders().getFirst("Idempotent-Replay"));
        assertEquals(first.getBody().get("txnId"), again.getBody().get("txnId"));
        assertEquals(1_234, ((Number) get("/wallet/account", bob, "USER").getBody().get("balanceMinor")).longValue());
        var reused = post("/wallet/transfers", alice, "USER", key, Map.of("toAccountId", bobAccount, "amountMinor", 9));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, reused.getStatusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.getBody().get("error"));
    }

    @Test
    void validationAndMissingHeaders() {
        long u = 20_010;
        assertEquals(HttpStatus.BAD_REQUEST, post("/wallet/transfers", u, "USER", null, Map.of("toAccountId", 5, "amountMinor", 5)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post("/wallet/transfers", u, "USER", "k", Map.of("toAccountId", 5, "amountMinor", -5)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post("/wallet/transfers", u, "USER", "k", Map.of("amountMinor", 5)).getStatusCode());
        var noIdentity = rest.exchange("/wallet/account", HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noIdentity.getStatusCode());
    }

    @Test
    void insufficientFundsIs422AndDeclinedIs422() {
        long a = 20_020, b = 20_021;
        post("/wallet/topups", a, "USER", "t-" + UUID.randomUUID(), Map.of("amountMinor", 100));
        long bAcc = ((Number) get("/wallet/account", b, "USER").getBody().get("accountId")).longValue();
        var r = post("/wallet/transfers", a, "USER", "k-" + UUID.randomUUID(), Map.of("toAccountId", bAcc, "amountMinor", 101));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode());
        assertEquals("INSUFFICIENT_FUNDS", r.getBody().get("error"));
        fraud.next = new FraudGate.Verdict(FraudGate.Decision.DECLINE, 100, List.of("BLOCKED_COUNTRY: x"));
        var d = post("/wallet/transfers", a, "USER", "k-" + UUID.randomUUID(), Map.of("toAccountId", bAcc, "amountMinor", 50));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, d.getStatusCode());
        assertEquals("DECLINED", d.getBody().get("status"));
    }

    @Test
    void adminEndpointsRequireAdminRole() {
        assertEquals(HttpStatus.FORBIDDEN, get("/wallet/admin/flagged", 20_030, "USER").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("/wallet/admin/reconciliation", 20_030, "USER").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, post("/wallet/admin/transactions/" + UUID.randomUUID() + "/approve", 20_030, "USER", null, Map.of()).getStatusCode());
        var rec = get("/wallet/admin/reconciliation", 1, "ADMIN");
        assertEquals(HttpStatus.OK, rec.getStatusCode());
        assertEquals(true, rec.getBody().get("healthy"));
        assertEquals(HttpStatus.NOT_FOUND, post("/wallet/admin/transactions/" + UUID.randomUUID() + "/approve", 1, "ADMIN", null, Map.of()).getStatusCode());
    }

    @Test
    void heldTransferIsApprovedByAdminOverHttp() {
        long a = 20_040, b = 20_041;
        post("/wallet/topups", a, "USER", "t-" + UUID.randomUUID(), Map.of("amountMinor", 10_000));
        long bAcc = ((Number) get("/wallet/account", b, "USER").getBody().get("accountId")).longValue();
        fraud.next = new FraudGate.Verdict(FraudGate.Decision.REVIEW, 50, List.of("LARGE_AMOUNT: x"));
        var held = post("/wallet/transfers", a, "USER", "k-" + UUID.randomUUID(), Map.of("toAccountId", bAcc, "amountMinor", 4_000));
        assertEquals("HELD", held.getBody().get("status"));
        var ok = post("/wallet/admin/transactions/" + held.getBody().get("txnId") + "/approve", 1, "ADMIN", null, Map.of());
        assertEquals("COMPLETED", ok.getBody().get("status"));
        assertEquals(4_000, ((Number) get("/wallet/account", b, "USER").getBody().get("balanceMinor")).longValue());
    }
}
