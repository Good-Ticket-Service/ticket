package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 분개 헤더. 업무 1건(판매·환불·상계·지급)마다 하나이고 업무 키 UNIQUE로 한 번만 들어간다(settlement-design 3절).
 * 줄은 차변 합 = 대변 합을 만족할 때만 만들고, 저장한 뒤에는 수정·삭제하지 않는다. 정정은 원거래를 가리키는 반대 분개로 한다.
 * 소프트 삭제 대상이 아니라 @SQLRestriction을 붙이지 않는다(DDL이 deleted_at IS NULL을 강제한다).
 */
@Getter
@Entity
@Table(name = "p_ledger_transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerTransaction extends BaseEntity {

    /** 줄 순서: 차변 먼저, 같은 쪽은 계정 선언 순서. 같은 입력이면 line_no가 항상 같다. */
    private static final Comparator<LedgerLine> LINE_ORDER =
            Comparator.comparing((LedgerLine line) -> !line.isDebit()).thenComparing(LedgerLine::account);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerSourceType sourceType;

    @Column(nullable = false)
    private Long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerAction action;

    @Column(nullable = false)
    private Long sellerId;

    private Long paymentId;
    private Long refundId;
    private Long statementId;
    private Long payoutId;

    /** 판매·환불 분개가 쓴 정책 버전. 여러 거래를 묶는 상계·지급은 없다. */
    @Column(length = 40)
    private String policyVersion;

    /** 반대 분개가 상쇄하는 원거래. */
    private Long reversesTransactionId;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    private List<LedgerEntry> entries = new ArrayList<>();

    /** LedgerJournals만 부른다. 줄 균형·정렬을 여기서 한 번에 검증한다. */
    static LedgerTransaction create(
            LedgerKey key,
            Long sellerId,
            Long paymentId,
            Long refundId,
            Long statementId,
            Long payoutId,
            String policyVersion,
            Long reversesTransactionId,
            LocalDateTime occurredAt,
            List<LedgerLine> lines) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (lines.isEmpty()) throw new IllegalArgumentException("줄이 없는 분개는 만들지 않는다");
        long debit = 0;
        long credit = 0;
        for (LedgerLine line : lines) {
            debit = Math.addExact(debit, line.debit());
            credit = Math.addExact(credit, line.credit());
        }
        if (debit != credit) throw new IllegalArgumentException("차변 합과 대변 합이 다르다: " + key);
        LedgerTransaction tx = new LedgerTransaction();
        tx.sourceType = key.sourceType();
        tx.sourceId = key.sourceId();
        tx.action = key.action();
        tx.sellerId = sellerId;
        tx.paymentId = paymentId;
        tx.refundId = refundId;
        tx.statementId = statementId;
        tx.payoutId = payoutId;
        tx.policyVersion = policyVersion;
        tx.reversesTransactionId = reversesTransactionId;
        tx.occurredAt = occurredAt;
        List<LedgerLine> ordered = lines.stream().sorted(LINE_ORDER).toList();
        for (int i = 0; i < ordered.size(); i++) tx.entries.add(new LedgerEntry(i + 1, ordered.get(i)));
        return tx;
    }

    public LedgerKey key() {
        return new LedgerKey(sourceType, sourceId, action);
    }

    public List<LedgerLine> lines() {
        return entries.stream().map(LedgerEntry::line).toList();
    }

    /**
     * 같은 업무 키로 다시 들어온 분개가 이미 저장한 것과 같은 업무인지. 발생 시각은 재시도마다 달라질 수 있어 비교하지 않는다.
     * 금액·참조가 다르면 중복이 아니라 충돌이다.
     */
    public boolean sameMeaningAs(LedgerTransaction other) {
        return key().equals(other.key())
                && sellerId.equals(other.sellerId)
                && Objects.equals(paymentId, other.paymentId)
                && Objects.equals(refundId, other.refundId)
                && Objects.equals(statementId, other.statementId)
                && Objects.equals(payoutId, other.payoutId)
                && Objects.equals(policyVersion, other.policyVersion)
                && Objects.equals(reversesTransactionId, other.reversesTransactionId)
                && lines().equals(other.lines());
    }
}
