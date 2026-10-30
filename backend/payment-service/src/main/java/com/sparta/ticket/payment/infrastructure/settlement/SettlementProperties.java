package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.payment.domain.model.settlement.BusinessCalendar;
import com.sparta.ticket.payment.domain.model.settlement.SettlementPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 정산 정책·영업일 달력 버전. 과거 버전을 지우지 않고 새 버전을 덧붙여 바꾼다(이미 만든 정산 항목은 고정한 버전을 쓴다).
 * 값이 틀리면 기동하지 않는다. 승인 중에 정책 오류로 결제가 막히는 대신 배포 시점에 드러내기 위해서다.
 */
@ConfigurationProperties("payment.settlement")
public record SettlementProperties(List<Policy> policies, List<Calendar> calendars) {

    public record Policy(
            String version,
            LocalDateTime effectiveFrom,
            int commissionRateBp,
            int vatRateBp,
            int pgRateBp,
            int pgVatRateBp,
            boolean pgFeeRefundedOnCancel,
            int eligibleBusinessDays) {
        SettlementPolicy toDomain() {
            return new SettlementPolicy(
                    version,
                    effectiveFrom,
                    commissionRateBp,
                    vatRateBp,
                    pgRateBp,
                    pgVatRateBp,
                    pgFeeRefundedOnCancel,
                    eligibleBusinessDays);
        }
    }

    public record Calendar(
            String version, LocalDateTime effectiveFrom, LocalDate coveredUntil, Set<LocalDate> holidays) {
        BusinessCalendar toDomain() {
            return new BusinessCalendar(version, effectiveFrom, coveredUntil, holidays);
        }
    }
}
