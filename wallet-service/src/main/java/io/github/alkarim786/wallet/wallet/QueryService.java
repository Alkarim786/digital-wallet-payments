package io.github.alkarim786.wallet.wallet;

import io.github.alkarim786.wallet.wallet.Model.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QueryService {
    private final LedgerRepository repo;

    public QueryService(LedgerRepository repo) {
        this.repo = repo;
    }

    public Account account(long userId) {
        return repo.ensureUserAccount(userId);
    }

    public Page<TxnView> history(long userId, HistoryFilter f, int page, int size) {
        Account a = repo.ensureUserAccount(userId);
        return repo.history(a.id(), f, Math.max(0, page), Math.min(100, Math.max(1, size)));
    }

    /** Daily sent/received totals (zero-filled so charts have no gaps) and spend by category. */
    public Analytics analytics(long userId, int days) {
        int n = Math.min(365, Math.max(1, days));
        Account a = repo.ensureUserAccount(userId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant since = today.minusDays(n - 1L).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, DayTotal> byDate = repo.dailyTotals(a.id(), since).stream().collect(Collectors.toMap(DayTotal::date, Function.identity()));
        List<DayTotal> series = new ArrayList<>();
        long sent = 0, received = 0;
        for (int i = n - 1; i >= 0; i--) {
            String d = today.minus(i, ChronoUnit.DAYS).toString();
            DayTotal t = byDate.getOrDefault(d, new DayTotal(d, 0, 0));
            series.add(t);
            sent += t.sentMinor();
            received += t.receivedMinor();
        }
        return new Analytics(series, repo.categoryTotals(a.id(), since), sent, received);
    }

    public List<TxnView> flagged() {
        return repo.flagged(200);
    }

    public Reconciliation reconcile() {
        return repo.reconcile();
    }
}
