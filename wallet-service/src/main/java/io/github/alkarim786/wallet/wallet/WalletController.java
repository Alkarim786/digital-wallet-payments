package io.github.alkarim786.wallet.wallet;

import io.github.alkarim786.wallet.common.Headers;
import io.github.alkarim786.wallet.common.Role;
import io.github.alkarim786.wallet.wallet.Model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Identity comes from headers injected by the gateway after JWT validation. This service must therefore only be
 * reachable through the gateway (compose publishes only the gateway's port).
 */
@RestController
@RequestMapping("/wallet")
public class WalletController {

    public record TransferRequest(@NotNull @Positive Long toAccountId, @NotNull @Positive Long amountMinor,
                                  @Size(max = 40) String category, @Size(max = 140) String note) {}

    public record TopUpRequest(@NotNull @Positive Long amountMinor) {}

    public record AccountView(long accountId, long balanceMinor, String currency) {}

    private final TransferService transfers;
    private final QueryService queries;

    public WalletController(TransferService transfers, QueryService queries) {
        this.transfers = transfers;
        this.queries = queries;
    }

    @GetMapping("/account")
    public AccountView account(@RequestHeader(Headers.USER_ID) long userId) {
        var a = queries.account(userId);
        return new AccountView(a.id(), a.balanceMinor(), a.currency());
    }

    @PostMapping("/topups")
    public ResponseEntity<TransferResult> topUp(@RequestHeader(Headers.USER_ID) long userId,
                                                @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String key,
                                                @Valid @RequestBody TopUpRequest body) {
        return respond(transfers.topUp(userId, body.amountMinor(), key));
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResult> transfer(@RequestHeader(Headers.USER_ID) long userId,
                                                   @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String key,
                                                   @RequestHeader(value = Headers.COUNTRY, required = false) String country,
                                                   @Valid @RequestBody TransferRequest body) {
        var cmd = new TransferCommand(body.toAccountId(), body.amountMinor(), body.category(), body.note());
        return respond(transfers.transfer(userId, cmd, key, country));
    }

    private ResponseEntity<TransferResult> respond(Outcome o) {
        return ResponseEntity.status(o.response().status())
                .header("Idempotent-Replay", String.valueOf(o.replayed()))
                .body(o.response().body());
    }

    @GetMapping("/transactions")
    public Page<TxnView> history(@RequestHeader(Headers.USER_ID) long userId,
                                 @RequestParam(required = false) String direction,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return queries.history(userId, new HistoryFilter(direction, status, type, category, from, to), page, size);
    }

    @GetMapping("/analytics")
    public Analytics analytics(@RequestHeader(Headers.USER_ID) long userId, @RequestParam(defaultValue = "30") int days) {
        return queries.analytics(userId, days);
    }

    // ------------------------------------------------------------------------------------- admin

    private void requireAdmin(String role) {
        if (!Role.ADMIN.name().equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "admin role required");
        }
    }

    @GetMapping("/admin/flagged")
    public List<TxnView> flagged(@RequestHeader(Headers.USER_ROLE) String role) {
        requireAdmin(role);
        return queries.flagged();
    }

    @PostMapping("/admin/transactions/{id}/approve")
    public TransferResult approve(@RequestHeader(Headers.USER_ROLE) String role, @PathVariable UUID id) {
        requireAdmin(role);
        return transfers.approve(id);
    }

    @PostMapping("/admin/transactions/{id}/reject")
    public TransferResult reject(@RequestHeader(Headers.USER_ROLE) String role, @PathVariable UUID id) {
        requireAdmin(role);
        return transfers.reject(id);
    }

    @GetMapping("/admin/reconciliation")
    public Reconciliation reconciliation(@RequestHeader(Headers.USER_ROLE) String role) {
        requireAdmin(role);
        return queries.reconcile();
    }
}
