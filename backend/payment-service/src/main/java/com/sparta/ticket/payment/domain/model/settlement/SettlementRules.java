package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 승인 1건을 정산 항목으로 만드는 규칙. 정책·달력 버전을 승인 시각으로 고르고 금액·정산 가능일을 정한다.
 * DB를 바꾸지 않는 순수 계산이라, 결제 승인 트랜잭션에서 저장 전에 실패를 분기할 수 있다.
 */
public final class SettlementRules {

    private final SettlementPolicyBook policies;
    private final BusinessCalendarBook calendars;

    public SettlementRules(SettlementPolicyBook policies, BusinessCalendarBook calendars) {
        this.policies = policies;
        this.calendars = calendars;
    }

    /** 승인 스냅샷을 저장할 SALE로 만든다. 계산 실패도 예외가 아니라 UNPOSTED 항목이다. */
    public SettlementItem saleFor(SaleSource source) {
        Optional<SettlementPolicy> policy = policies.at(source.approvedAt());
        Optional<BusinessCalendar> calendar = calendars.at(source.approvedAt());
        if (policy.isEmpty() || calendar.isEmpty())
            return SettlementItem.unpostedSale(source, SettlementFailure.POLICY_NOT_FOUND);
        SaleAmounts.Result result = SaleAmounts.calculate(
                source.gross(), source.discount(), source.bookingFee(), source.paid(), policy.get());
        if (result instanceof SaleAmounts.Rejected rejected)
            return SettlementItem.unpostedSale(source, rejected.reason());
        SaleAmounts amounts = ((SaleAmounts.Calculated) result).amounts();
        if (source.scheduleStartAt() == null)
            return SettlementItem.postedSale(
                    source, amounts, policy.get(), calendar.get(), null, SettlementHoldReason.MISSING_SCHEDULE_START);
        Optional<LocalDateTime> eligibleAt =
                calendar.get().eligibleAt(source.scheduleStartAt(), policy.get().eligibleBusinessDays());
        return SettlementItem.postedSale(
                source,
                amounts,
                policy.get(),
                calendar.get(),
                eligibleAt.orElse(null),
                eligibleAt.isPresent() ? null : SettlementHoldReason.CALENDAR_NOT_COVERED);
    }

    /** 정산 항목에 고정한 정책 버전. 손익 조회가 과거 요율을 쓰게 한다. */
    public Optional<SettlementPolicy> policy(String version) {
        return policies.byVersion(version);
    }
}
