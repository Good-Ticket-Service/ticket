package com.sparta.ticket.payment.application.port.pgsettlement;

import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 결제 소유 데이터의 읽기 전용 스냅샷. 원격 호출·결제 상태 변경은 하지 않는다. */
public interface PgSettlementFacts {
    record Facts(
            long id,
            String paymentKey,
            String orderId,
            String status,
            boolean pending,
            Long amount,
            LocalDateTime approvedAt,
            Long refundAmount,
            LocalDateTime refundedAt,
            boolean salePosted,
            boolean refundPosted) {
        public PgSettlement.Expected event(String type) {
            return new PgSettlement.Expected(
                    paymentKey,
                    orderId,
                    type,
                    "APPROVAL".equals(type) ? amount == null ? 0 : amount : refundAmount == null ? 0 : -refundAmount,
                    "APPROVAL".equals(type) ? approvedAt : refundedAt,
                    pending,
                    "APPROVAL".equals(type) ? salePosted : refundPosted,
                    status);
        }
    }

    Map<String, Facts> find(String provider, List<String> paymentKeys);

    List<Facts> candidates(String provider, LocalDateTime from, LocalDateTime until, long after, int size);
}
