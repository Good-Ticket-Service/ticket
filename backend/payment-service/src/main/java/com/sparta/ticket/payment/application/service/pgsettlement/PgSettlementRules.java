package com.sparta.ticket.payment.application.service.pgsettlement;

import com.sparta.ticket.payment.domain.model.pgsettlement.PgFeePolicy;
import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement.Finding;
import com.sparta.ticket.payment.domain.model.settlement.BusinessCalendarBook;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PgSettlementRules {
    private final PgSettlementProperties properties;
    private final BusinessCalendarBook calendars;

    public PgFeePolicy policy(String provider, LocalDateTime approvedAt) {
        return properties.fees().stream()
                .filter(p -> p.provider().equals(provider) && !p.effectiveFrom().isAfter(approvedAt))
                .max(Comparator.comparing(PgFeePolicy::effectiveFrom))
                .orElseThrow(() -> new IllegalStateException("PG 수수료 정책의 적용 범위 밖이다"));
    }

    public LocalDate date(LocalDateTime at, int days) {
        return calendars
                .at(at)
                .orElseThrow(() -> new IllegalStateException("영업일 달력이 없다"))
                .eligibleAt(at, days)
                .orElseThrow(() -> new IllegalStateException("영업일 달력 범위 밖이다"))
                .toLocalDate();
    }

    public PgSettlement.Decision decide(
            String provider,
            LocalDate actualDate,
            PgSettlement.Line line,
            PgSettlement.Expected expected,
            LocalDateTime approvedAt) {
        if (expected == null) return new PgSettlement.Decision(Finding.ONLY_IN_PG, null, null, null, null, 0);
        if (expected.occurredAt() == null || approvedAt == null)
            return new PgSettlement.Decision(
                    expected.pending()
                            ? Finding.PENDING
                            : "APPROVAL".equals(line.type()) && "FAILED".equals(expected.paymentStatus())
                                    ? Finding.ONLY_IN_PG
                                    : Finding.STATUS_MISMATCH,
                    expected.amount(),
                    null,
                    null,
                    null,
                    0);
        PgFeePolicy policy = policy(provider, approvedAt);
        LocalDate approvalDate = date(approvedAt, policy.businessDays());
        LocalDate expectedDate = "APPROVAL".equals(line.type())
                ? approvalDate
                : expected.occurredAt().isBefore(approvalDate.atStartOfDay())
                        ? approvalDate
                        : date(expected.occurredAt(), policy.businessDays());
        long fee = "APPROVAL".equals(line.type()) ? policy.fee(expected.amount()) : -policy.fee(-expected.amount());
        Finding finding = expected.pending()
                ? Finding.PENDING
                : !line.orderId().equals(expected.orderId())
                        ? Finding.STATUS_MISMATCH
                        : line.amount() != expected.amount()
                                ? Finding.AMOUNT_MISMATCH
                                : line.fee() != fee
                                        ? Finding.FEE_MISMATCH
                                        : !actualDate.equals(expectedDate) ? Finding.DATE_MISMATCH : Finding.MATCHED;
        // Note: 수수료·날짜 불일치가 확인된 원금까지 가계정으로 보내지는 않는다.
        long clearing = !expected.pending()
                        && expected.ledgerPosted()
                        && line.orderId().equals(expected.orderId())
                        && line.amount() == expected.amount()
                ? line.amount()
                : 0;
        return new PgSettlement.Decision(finding, expected.amount(), fee, expectedDate, policy.version(), clearing);
    }
}
