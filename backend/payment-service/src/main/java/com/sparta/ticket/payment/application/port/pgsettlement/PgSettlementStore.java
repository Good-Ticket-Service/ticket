package com.sparta.ticket.payment.application.port.pgsettlement;

import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PgSettlementStore {
    record StoredLine(
            long id,
            long dayId,
            String provider,
            LocalDate date,
            String dayStatus,
            PgSettlement.Line line,
            long clearing,
            long suspense,
            boolean adjusted) {}

    record Day(long id, String provider, LocalDate date, String status, PgSettlement.Totals totals) {}

    record Verdict(StoredLine line, PgSettlement.Decision decision) {}

    record Balances(
            long bookCash,
            long pgClearing,
            long sellerPayable,
            long pgPayable,
            long pgSuspense,
            boolean bankVerified) {}

    long receive(PgSettlement.Page page);

    void verifyReceived(String provider, LocalDate date);

    List<StoredLine> unmatched(String provider, LocalDate date, int size);

    List<StoredLine> unresolved(String provider, long after, int size);

    void decide(StoredLine line, PgSettlement.Decision decision);

    void decideBatch(List<Verdict> verdicts);

    boolean contains(String provider, String lineKey);

    java.util.Set<String> containsBatch(String provider, List<String> keys);

    void missing(String provider, String lineKey, PgSettlement.Decision decision, LocalDate observedDate);

    void matched(String provider, LocalDate date);

    Day lockDay(String provider, LocalDate date);

    void checkPostingOrder(Day day);

    long clearing(long dayId);

    long suspense(long dayId);

    void posted(long dayId);

    void adjusted(long lineId, long amount);

    void closePairedSuspense(String provider);

    List<Map<String, Object>> days(String provider, LocalDate after, int size);

    List<Map<String, Object>> findings(String provider, long after, int size, String status);

    Balances balances();

    void resolve(long id, String reason, String actor);

    List<Map<String, Object>> metrics();
}
