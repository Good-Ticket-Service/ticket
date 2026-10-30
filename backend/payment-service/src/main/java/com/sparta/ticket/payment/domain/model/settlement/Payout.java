package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자 지급(settlement-design 7.2절). 정산서 하나에 하나다. 이 단계는 발행 때 만드는 READY까지만 다룬다.
 * 실행권·멱등 키·계좌 스냅샷·결과 확정은 지급 실행 작업에서 더한다.
 */
@Getter
@Entity
@Table(name = "p_payouts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long statementId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(nullable = false)
    private int attempt;

    /** 처음 실행할 수 있는 시각(다음 영업일 지급 시작 시각). 달력 범위를 넘으면 비어 있고 지급 실행이 보류한다. */
    private LocalDateTime nextAttemptAt;

    @Version
    @Column(nullable = false)
    private long version;

    public static Payout ready(SettlementStatement statement, LocalDateTime nextAttemptAt) {
        if (statement.getStatus() != StatementStatus.ISSUED)
            throw new IllegalArgumentException("지급할 금액이 있는 정산서만 지급을 만든다");
        Payout payout = new Payout();
        payout.statementId = statement.getId();
        payout.sellerId = statement.getSellerId();
        payout.amount = statement.getPayableAmount();
        payout.status = PayoutStatus.READY;
        payout.nextAttemptAt = nextAttemptAt;
        return payout;
    }
}
