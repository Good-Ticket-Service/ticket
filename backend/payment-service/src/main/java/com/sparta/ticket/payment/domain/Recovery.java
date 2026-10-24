package com.sparta.ticket.payment.domain;

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
 * Note: 결과 불명과 자동 처리 중단은 상태(status)로 만들지 않고 이 값으로 표현한다.
 * 상태는 업무 결과(승인·거절)만 나타내고, "다시 볼 시각"과 "자동 처리 중단"은 운영 정보라 따로 둔다.
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recovery {

    private static final int ERROR_MAX_LENGTH = 80;

    /** 복구 잡이 다음에 볼 시각. 점유할 때도 이 값을 미뤄 다른 인스턴스가 같은 행을 잡지 않게 한다. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** 자동 처리 중단(give-up) 기준 시각. 관리자가 차단을 풀면 다시 센다. */
    @Column(name = "recovery_started_at")
    private LocalDateTime startedAt;

    @Column(name = "recovery_attempts", nullable = false)
    private int attempts;

    @Column(name = "recovery_blocked", nullable = false)
    private boolean blocked;

    @Column(name = "last_error", length = ERROR_MAX_LENGTH)
    private String lastError;

    static Recovery none() {
        return new Recovery();
    }

    void start(LocalDateTime now, LocalDateTime firstAttemptAt) {
        this.startedAt = now;
        this.nextAttemptAt = firstAttemptAt;
        this.attempts = 0;
        this.blocked = false;
        this.lastError = null;
    }

    void claimUntil(LocalDateTime until) {
        this.nextAttemptAt = until;
    }

    void defer(String error, LocalDateTime nextAttemptAt) {
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    void block(String reason) {
        this.blocked = true;
        this.lastError = truncate(reason);
    }

    void unblock(LocalDateTime now) {
        this.blocked = false;
        this.startedAt = now;
        this.nextAttemptAt = now;
    }

    /** 확정되면 더는 복구 대상이 아니다. 시도 횟수와 마지막 오류는 기록으로 남긴다. */
    void finish() {
        this.nextAttemptAt = null;
        this.blocked = false;
    }

    boolean isGivenUp(LocalDateTime now, Duration giveUpAfter) {
        return startedAt != null && !now.isBefore(startedAt.plus(giveUpAfter));
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_MAX_LENGTH);
    }
}
