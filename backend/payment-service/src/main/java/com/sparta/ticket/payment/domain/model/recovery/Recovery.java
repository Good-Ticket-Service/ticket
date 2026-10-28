package com.sparta.ticket.payment.domain.model.recovery;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결과를 모르는 결제·환불을 복구 잡이 다시 확인하기 위한 값. 결제와 환불이 같은 규칙을 쓴다.
 *
 * Note: 결과 불명은 상태(status)로 만들지 않고 이 값으로 표현한다.
 * 상태는 업무 결과(승인·거절)만 나타내고, "다시 볼 시각"은 운영 정보라 따로 둔다.
 * 자동 처리 중단(차단)은 환불에만 있어 이 값에 두지 않고 Refund가 가진다. 결제는 시한이 지나면 FAILED로 확정하고 차단하지 않는다.
 *
 * 변경 메서드는 Payment·Refund가 다른 패키지에서 부르므로 public이다. 대신 두 엔티티는 이 객체를 getter로 내주지 않고
 * 필요한 값만 꺼내 준다. 그래서 바깥에서는 엔티티의 메서드(deferRecovery 등)를 거쳐야만 바꿀 수 있고,
 * 엔티티가 그때 상태를 함께 검사한다.
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recovery {

    private static final int ERROR_MAX_LENGTH = 80;

    /** 복구 잡이 다음에 볼 시각. 점유할 때도 이 값을 미뤄 다른 인스턴스가 같은 행을 잡지 않게 한다. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** 복구를 시작한 시각. 환불은 저빈도 확인 전환 기준이며 같은 취소 키의 단순 재개로 초기화하지 않는다. 결제는 PG 승인 호출 시각과 같다. */
    @Column(name = "recovery_started_at")
    private LocalDateTime startedAt;

    @Column(name = "recovery_attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = ERROR_MAX_LENGTH)
    private String lastError;

    public static Recovery none() {
        return new Recovery();
    }

    public void start(LocalDateTime now, LocalDateTime firstAttemptAt) {
        this.startedAt = now;
        this.nextAttemptAt = firstAttemptAt;
        this.attempts = 0;
        this.lastError = null;
    }

    public void claimUntil(LocalDateTime until) {
        this.nextAttemptAt = until;
    }

    public void defer(String error, LocalDateTime nextAttemptAt) {
        if (this.attempts < Integer.MAX_VALUE) this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    /** 자동 처리를 멈춘 이유를 남긴다. 멈춤 여부는 호출한 엔티티가 따로 가진다. */
    public void recordError(String error) {
        this.lastError = truncate(error);
    }

    /** 차단을 푼 뒤 다시 세기 시작한다. 바로 복구 대상이 된다. */
    public void restart(LocalDateTime now) {
        this.startedAt = now;
        this.nextAttemptAt = now;
    }

    /** 확정되면 더는 복구 대상이 아니다. 시도 횟수와 마지막 오류는 기록으로 남긴다. */
    public void finish() {
        this.nextAttemptAt = null;
    }

    public boolean isGivenUp(LocalDateTime now, Duration giveUpAfter) {
        return startedAt != null && !now.isBefore(startedAt.plus(giveUpAfter));
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_MAX_LENGTH);
    }
}
