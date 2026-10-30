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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정산서 명세행. SALE은 판매자 몫 N 전액, DEDUCTION은 이 정산서에서 상계한 몫만 담는다.
 * SALE은 한 정산서에만 들어간다(부분 UNIQUE). DEDUCTION은 여러 정산서에 나눠 상계될 수 있다.
 */
@Getter
@Entity
@Table(name = "p_settlement_statement_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementStatementLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long statementId;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementItemKind kind;

    @Column(nullable = false)
    private long amount;

    public static SettlementStatementLine of(SettlementStatement statement, SettlementItem item, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("0원 명세행은 만들지 않는다");
        SettlementStatementLine line = new SettlementStatementLine();
        line.statementId = statement.getId();
        line.itemId = item.getId();
        line.kind = item.getKind();
        line.amount = amount;
        return line;
    }
}
