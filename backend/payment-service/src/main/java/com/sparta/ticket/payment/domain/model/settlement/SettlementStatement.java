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
 * 판매자·회차별 정산서(settlement-design 3절 4번·5절). 발행 때 판매 합계·상계액·지급액을 고정하고 바꾸지 않는다.
 * 늦게 정상화된 SALE은 기존 정산서를 고치지 않고 같은 회차의 다음 sequence로 발행한다.
 */
@Getter
@Entity
@Table(name = "p_settlement_statements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementStatement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private int sequence;

    /** 발행 잡 시작 시각. 이 시각까지 정산 가능해진 SALE만 담는다. */
    @Column(nullable = false)
    private LocalDateTime cutoff;

    /** 편입한 SALE의 판매자 몫 N 합. */
    @Column(nullable = false)
    private long saleTotal;

    @Column(nullable = false)
    private long offsetTotal;

    @Column(nullable = false)
    private long payableAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementStatus status;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /** 지급액 = 판매 합계 − 상계액. 0이면 지급 없이 NO_PAYOUT이다. */
    public static SettlementStatement issue(
            Long sellerId,
            Long scheduleId,
            int sequence,
            LocalDateTime cutoff,
            long saleTotal,
            long offsetTotal,
            LocalDateTime issuedAt) {
        if (saleTotal <= 0) throw new IllegalArgumentException("판매가 없는 정산서는 발행하지 않는다");
        if (offsetTotal < 0 || offsetTotal > saleTotal) throw new IllegalArgumentException("상계액은 0 이상 판매 합계 이하다");
        if (sequence < 0) throw new IllegalArgumentException("정산서 순번은 0부터다");
        SettlementStatement statement = new SettlementStatement();
        statement.sellerId = sellerId;
        statement.scheduleId = scheduleId;
        statement.sequence = sequence;
        statement.cutoff = cutoff;
        statement.saleTotal = saleTotal;
        statement.offsetTotal = offsetTotal;
        statement.payableAmount = saleTotal - offsetTotal;
        statement.status = statement.payableAmount > 0 ? StatementStatus.ISSUED : StatementStatus.NO_PAYOUT;
        statement.issuedAt = issuedAt;
        return statement;
    }
}
