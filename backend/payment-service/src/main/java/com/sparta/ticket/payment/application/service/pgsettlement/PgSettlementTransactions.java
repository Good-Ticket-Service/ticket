package com.sparta.ticket.payment.application.service.pgsettlement;

import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementFacts;
import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementStore;
import com.sparta.ticket.payment.application.service.settlement.LedgerTransactions;
import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import com.sparta.ticket.payment.domain.model.settlement.BusinessCalendarBook;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PgSettlementTransactions {
    private final PgSettlementStore store;
    private final PgSettlementFacts facts;
    private final PgSettlementRules rules;
    private final PgSettlementProperties properties;
    private final BusinessCalendarBook calendars;
    private final LedgerTransactions ledger;
    private final JobRepository jobs;
    private final Clock clock;

    /** 페이지와 cursor를 같은 DB 트랜잭션에 커밋한다. HTTP 호출은 이 메서드에 들어오기 전에 끝낸다. */
    @Transactional
    public void receive(PgSettlement.Page page, StepExecution step) {
        store.receive(page);
        var context = step.getExecutionContext();
        context.putString("snapshot", page.snapshotId());
        if (page.nextCursor() == null) {
            store.verifyReceived(page.provider(), page.settlementDate());
            context.putString("eof", "true");
        } else context.putString("cursor", page.nextCursor());
        jobs.updateExecutionContext(step);
    }

    @Transactional
    public int reconcile(String provider, LocalDate date) {
        var lines = store.unmatched(provider, date, properties.pageSize());
        decide(provider, lines);
        if (lines.isEmpty()) store.matched(provider, date);
        return lines.size();
    }

    @Transactional
    public long revisit(String provider, long after) {
        var lines = store.unresolved(provider, after, properties.pageSize());
        decide(provider, lines);
        if (lines.isEmpty()) store.closePairedSuspense(provider);
        return lines.isEmpty() ? -1 : lines.get(lines.size() - 1).id();
    }

    private void decide(String provider, List<PgSettlementStore.StoredLine> lines) {
        var snapshots = facts.find(
                provider,
                lines.stream().map(l -> l.line().paymentKey()).distinct().toList());
        var batch = new java.util.ArrayList<PgSettlementStore.Verdict>();
        for (var line : lines) {
            var fact = snapshots.get(line.line().paymentKey());
            var decision = rules.decide(
                    provider,
                    line.date(),
                    line.line(),
                    fact == null ? null : fact.event(line.line().type()),
                    fact == null ? null : fact.approvedAt());
            if (!"POSTED".equals(line.dayStatus())) batch.add(new PgSettlementStore.Verdict(line, decision));
            else store.decide(line, decision);
            if ("POSTED".equals(line.dayStatus())
                    && line.suspense() != 0
                    && !line.adjusted()
                    && decision.clearingAmount() == line.line().amount()) {
                ledger.post(LedgerJournals.pgAdjustment(line.id(), line.suspense(), LocalDateTime.now(clock)));
                store.adjusted(line.id(), line.line().amount());
            }
        }
        store.decideBatch(batch);
    }

    @Transactional
    public long missing(String provider, LocalDate date, long after) {
        LocalDate from = date;
        int remaining = properties.fees().stream()
                        .filter(p -> p.provider().equals(provider))
                        .mapToInt(p -> p.businessDays())
                        .max()
                        .orElseThrow()
                + properties.graceBusinessDays()
                + 2;
        while (remaining > 0 && from.isAfter(properties.startDate())) {
            from = from.minusDays(1);
            if (calendars.at(from.atStartOfDay()).orElseThrow().isBusinessDay(from)) remaining--;
        }
        var candidates = facts.candidates(
                provider, from.atStartOfDay(), date.plusDays(1).atStartOfDay(), after, properties.pageSize());
        var known = store.containsBatch(
                provider,
                candidates.stream()
                        .flatMap(c ->
                                java.util.stream.Stream.of(c.paymentKey() + ":APPROVAL", c.paymentKey() + ":CANCEL"))
                        .toList());
        for (var candidate : candidates) {
            if (candidate.approvedAt() == null) continue;
            for (String type : List.of("APPROVAL", "CANCEL")) {
                var expected = candidate.event(type);
                if (expected.occurredAt() == null || expected.amount() == 0 || known.contains(expected.lineKey()))
                    continue;
                var synthetic = new PgSettlement.Line(
                        expected.lineKey(),
                        expected.paymentKey(),
                        expected.orderId(),
                        type,
                        expected.amount(),
                        0,
                        expected.occurredAt());
                var calculation = rules.decide(provider, date, synthetic, expected, candidate.approvedAt());
                LocalDate expectedDate = calculation.expectedDate();
                if (expectedDate == null || expectedDate.isAfter(date)) continue;
                LocalDate due = properties.graceBusinessDays() == 0
                        ? expectedDate
                        : rules.date(expectedDate.atStartOfDay(), properties.graceBusinessDays());
                var finding = expected.pending() || due.isAfter(date)
                        ? PgSettlement.Finding.PENDING
                        : PgSettlement.Finding.ONLY_IN_OURS;
                if (finding == PgSettlement.Finding.ONLY_IN_OURS
                        && "CANCEL".equals(type)
                        && known.contains(candidate.paymentKey() + ":APPROVAL"))
                    finding = PgSettlement.Finding.STATUS_MISMATCH;
                store.missing(
                        provider,
                        expected.lineKey(),
                        new PgSettlement.Decision(
                                finding,
                                expected.amount(),
                                calculation.expectedFee(),
                                expectedDate,
                                calculation.policyVersion(),
                                0),
                        date);
            }
        }
        return candidates.isEmpty() ? -1 : candidates.get(candidates.size() - 1).id();
    }

    @Transactional
    public void post(String provider, LocalDate date) {
        var day = store.lockDay(provider, date);
        if ("POSTED".equals(day.status())) return;
        if (!"MATCHED".equals(day.status())) throw new IllegalStateException("명세 수집과 대사가 끝나지 않았다");
        store.checkPostingOrder(day);
        var t = day.totals();
        long clearing = store.clearing(day.id());
        long suspense = store.suspense(day.id());
        if (Math.addExact(clearing, suspense) != t.amount()) throw new IllegalStateException("명세 원금의 회계 배정이 맞지 않는다");
        LedgerJournals.pgSettlement(
                        day.id(),
                        t.payout(),
                        t.fee(),
                        clearing,
                        suspense,
                        t.carryIn(),
                        t.carryOut(),
                        date.atStartOfDay())
                .ifPresent(ledger::post);
        store.posted(day.id());
        store.closePairedSuspense(provider);
    }

    @Transactional(readOnly = true)
    public PgSettlementStore.Balances balances() {
        return store.balances();
    }

    @Transactional
    public void resolve(long id, String reason, String actor) {
        store.resolve(id, reason, actor);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> days(String provider, LocalDate after, int size) {
        return camelKeys(store.days(provider, after, size));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findings(String provider, long after, int size, String status) {
        return camelKeys(store.findings(provider, after, size, status));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> metrics() {
        return store.metrics();
    }

    private static List<Map<String, Object>> camelKeys(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    row.forEach((key, value) -> {
                        String[] words = key.split("_");
                        var name = new StringBuilder(words[0]);
                        for (int i = 1; i < words.length; i++)
                            name.append(Character.toUpperCase(words[i].charAt(0)))
                                    .append(words[i].substring(1));
                        result.put(name.toString(), value);
                    });
                    return result;
                })
                .toList();
    }
}
