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
import org.hibernate.annotations.SQLRestriction;

/**
 * 정산 항목. SALE은 승인된 결제 1건, DEDUCTION은 정산서 발행 뒤 환불 1건이다(settlement-design 3절).
 * 금액·정책·달력 버전은 만들 때 고정하고 바꾸지 않는다. 정책이 바뀌어도 과거 거래를 다시 계산하지 않는다.
 * 계산에 실패한 SALE도 결제 입력과 사유를 남겨(UNPOSTED) 누락이 아니라 드러난 오류로 만든다.
 */
@Getter
@Entity
@Table(name = "p_settlement_items")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementItemKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementItemStatus status;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long paymentId;

    private Long refundId;

    /** DEDUCTION이 되돌리는 SALE. */
    private Long originItemId;

    private Long performanceId;
    private Long scheduleId;
    private LocalDateTime scheduleStartAt;
    private LocalDateTime approvedAt;

    private Integer grossAmount;
    private Integer discountAmount;
    private Integer bookingFee;
    private Integer paidAmount;
    private Integer commission;
    private Integer commissionVat;
    private Integer sellerAmount;
    private Integer bookingFeeNet;
    private Integer bookingFeeVat;

    @Column(length = 40)
    private String policyVersion;

    @Column(length = 40)
    private String calendarVersion;

    private LocalDateTime eligibleAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private SettlementHoldReason holdReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 80)
    private SettlementFailure failureReason;

    private Long statementId;

    /** DEDUCTION의 차감 원금(원 SALE의 판매자 몫 N 전액). */
    private Long principal;

    /** DEDUCTION에서 아직 상계하지 못한 금액. */
    private Long remaining;

    @Version
    @Column(nullable = false)
    private long version;

    /**
     * 분개할 수 있는 SALE. 보류 사유가 없으면 PENDING, 있으면 HELD로 만든다.
     * 회차 시각이 없거나 달력 범위를 넘어 정산 가능일이 없으면 반드시 보류 사유가 있어야 한다.
     */
    public static SettlementItem postedSale(
            SaleSource source,
            SaleAmounts amounts,
            SettlementPolicy policy,
            BusinessCalendar calendar,
            LocalDateTime eligibleAt,
            SettlementHoldReason holdReason) {
        if (holdReason == null && eligibleAt == null)
            throw new IllegalArgumentException("정산 가능일이 없는 SALE은 보류 사유가 있어야 한다");
        if (amounts.gross() != source.gross()
                || amounts.discount() != source.discount()
                || amounts.bookingFee() != source.bookingFee()
                || amounts.paid() != source.paid()) throw new IllegalArgumentException("정산 금액이 결제 스냅샷과 다르다");
        SettlementItem item =
                sale(source, holdReason == null ? SettlementItemStatus.PENDING : SettlementItemStatus.HELD);
        item.commission = amounts.commission();
        item.commissionVat = amounts.commissionVat();
        item.sellerAmount = amounts.sellerAmount();
        item.bookingFeeNet = amounts.bookingFeeNet();
        item.bookingFeeVat = amounts.bookingFeeVat();
        item.policyVersion = policy.version();
        item.calendarVersion = calendar.version();
        item.eligibleAt = eligibleAt;
        item.holdReason = holdReason;
        return item;
    }

    /** 금액을 계산하지 못한 SALE. 결제 입력과 사유만 남기고 분개하지 않는다. */
    public static SettlementItem unpostedSale(SaleSource source, SettlementFailure reason) {
        if (reason == null) throw new IllegalArgumentException("계산 실패 사유가 없다");
        SettlementItem item = sale(source, SettlementItemStatus.UNPOSTED);
        item.failureReason = reason;
        return item;
    }

    /** 정산서에 들어간 SALE이 환불됐을 때 판매자 몫 N 전액을 돌려받을 차감. */
    public static SettlementItem deduction(SettlementItem sale, Long refundId) {
        if (sale.kind != SettlementItemKind.SALE || sale.status != SettlementItemStatus.ON_STATEMENT)
            throw new IllegalArgumentException("정산서에 들어간 SALE만 차감을 만든다");
        if (refundId == null) throw new IllegalArgumentException("차감할 환불이 없다");
        SettlementItem item = new SettlementItem();
        item.kind = SettlementItemKind.DEDUCTION;
        item.status = SettlementItemStatus.OPEN;
        item.sellerId = sale.sellerId;
        item.paymentId = sale.paymentId;
        item.refundId = refundId;
        item.originItemId = sale.id;
        item.principal = sale.sellerAmount.longValue();
        item.remaining = item.principal;
        return item;
    }

    private static SettlementItem sale(SaleSource source, SettlementItemStatus status) {
        SettlementItem item = new SettlementItem();
        item.kind = SettlementItemKind.SALE;
        item.status = status;
        item.sellerId = source.sellerId();
        item.paymentId = source.paymentId();
        item.performanceId = source.performanceId();
        item.scheduleId = source.scheduleId();
        item.scheduleStartAt = source.scheduleStartAt();
        item.approvedAt = source.approvedAt();
        item.grossAmount = source.gross();
        item.discountAmount = source.discount();
        item.bookingFee = source.bookingFee();
        item.paidAmount = source.paid();
        return item;
    }

    /** 분개한 SALE의 고정 금액. UNPOSTED·DEDUCTION에는 없다. */
    public SaleAmounts amounts() {
        if (kind != SettlementItemKind.SALE || status == SettlementItemStatus.UNPOSTED)
            throw new IllegalStateException("분개한 SALE만 정산 금액이 있다");
        return new SaleAmounts(
                grossAmount,
                discountAmount,
                bookingFee,
                paidAmount,
                commission,
                commissionVat,
                sellerAmount,
                bookingFeeNet,
                bookingFeeVat);
    }
}
