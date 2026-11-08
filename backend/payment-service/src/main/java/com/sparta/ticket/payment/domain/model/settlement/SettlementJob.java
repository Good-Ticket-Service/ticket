package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 정산 반영 작업. 결제 승인 확정·환불 완료와 같은 트랜잭션에 저장되고, 정산 워커가 별도 트랜잭션에서 처리한다.
 * 정산 쪽 오류가 결제 확정을 롤백하지 않게 하려는 분리다. 행은 {@code SettlementJobQueue}가 만든다.
 */
@Getter
@Entity
@Table(name = "p_settlement_jobs")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementJob extends BaseEntity {

    /** 오류 설명은 고정 형식의 짧은 값만 남긴다. 금액·계좌·PG 원문을 넣지 않는다. */
    private static final int ERROR_LENGTH = 80;

    @Id
    private Long id;

    @Column(nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementJobReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementJobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(length = ERROR_LENGTH)
    private String lastError;

    private LocalDateTime doneAt;

    public void complete(LocalDateTime now) {
        require(SettlementJobStatus.PENDING);
        this.status = SettlementJobStatus.DONE;
        this.doneAt = now;
        this.lastError = null;
    }

    /**
     * 실패를 기록한다. 한도에 닿으면 격리(BLOCKED)하고, 아니면 다음 실행 시각을 미룬다.
     *
     * @return 이번 실패로 격리됐으면 true
     */
    public boolean fail(LocalDateTime now, String error, int maxAttempts, Duration delay) {
        require(SettlementJobStatus.PENDING);
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), ERROR_LENGTH));
        if (attempts >= maxAttempts) {
            this.status = SettlementJobStatus.BLOCKED;
            return true;
        }
        this.nextAttemptAt = now.plus(delay);
        return false;
    }

    private void require(SettlementJobStatus expected) {
        if (status != expected)
            throw new IllegalStateException("정산 작업 상태가 " + expected + "가 아니다: " + id + " " + status);
    }
}
