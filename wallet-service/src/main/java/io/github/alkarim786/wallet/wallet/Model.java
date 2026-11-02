package io.github.alkarim786.wallet.wallet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Plain data carriers shared by the repository, service and controllers. */
public final class Model {
    private Model() {}

    public static final String USD = "USD";
    public static final long SYSTEM_ACCOUNT_ID = 1L;

    public record Account(long id, Long ownerUserId, String type, String currency, long balanceMinor) {}

    public record Txn(UUID id, String type, String status, long fromAccount, long toAccount, long initiatorUser,
                      long amountMinor, String currency, String category, String note, int fraudScore, String fraudReasons) {}

    /** What a user sees in their history. */
    public record TxnView(UUID id, String type, String status, String direction, long counterpartyAccount, long amountMinor,
                          String currency, String category, String note, int fraudScore, String fraudReasons, Instant createdAt) {}

    public record Page<T>(List<T> items, int page, int size, long total) {}

    public record TransferCommand(long toAccountId, long amountMinor, String category, String note) {}

    /** Returned (and stored for idempotent replay) for transfers and top-ups. */
    public record TransferResult(UUID txnId, String status, long amountMinor, long fromAccount, long toAccount,
                                 int fraudScore, List<String> reasons, long balanceMinor) {}

    /** HTTP status + body persisted under an Idempotency-Key. */
    public record StoredResponse(int status, TransferResult body) {}

    public record Outcome(StoredResponse response, boolean replayed) {}

    public record HistoryFilter(String direction, String status, String type, String category, Instant from, Instant to) {}

    public record DayTotal(String date, long sentMinor, long receivedMinor) {}

    public record CategoryTotal(String category, long sentMinor) {}

    public record Analytics(List<DayTotal> byDay, List<CategoryTotal> byCategory, long totalSentMinor, long totalReceivedMinor) {}

    public record Reconciliation(long sumOfAllBalances, long accountsWithDrift, long unbalancedTransactions, boolean healthy) {}
}
