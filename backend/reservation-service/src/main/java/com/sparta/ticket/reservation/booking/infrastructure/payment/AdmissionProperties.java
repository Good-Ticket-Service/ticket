package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.reservation.booking.application.port.AdmissionSettings;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 결제 준비 접수 제한 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 * 시간 값과 shed-above가 빠지면 기동이 실패한다. 빠졌을 때 0이 되는 enabled·resume-below·priority-rate는 0이어도 안전한 쪽(끔, 늦은 결제가 없을 때만 다시 받음, 만료 임박 통과 없음)이다.
 */
@Validated
@ConfigurationProperties(prefix = "reservation.booking.admission")
public record AdmissionProperties(
        boolean enabled,
        @NotNull @DurationMin(seconds = 1) Duration lateAfter,
        @Positive int shedAbove,
        @PositiveOrZero int resumeBelow,
        // 앞뒤 절반을 비교하므로 1초 주기 집계가 절반마다 한 번은 들어가야 한다
        @NotNull @DurationMin(seconds = 2) Duration trendWindow,
        @NotNull @DurationMin(seconds = 1) Duration retryAfter,
        @NotNull Duration maxRetryAfter,
        @NotNull @DurationMin(seconds = 1) Duration staleAfter,
        @NotNull Duration priorityWindow,
        @PositiveOrZero int priorityRate)
        implements AdmissionSettings {

    /** 같은 값이면 경계에서 거절과 받음을 매초 오가므로 다시 받는 기준이 더 낮아야 한다. */
    @AssertTrue(message = "resume-below는 shed-above보다 작아야 한다")
    public boolean isResumeBelowShedAbove() {
        return resumeBelow < shedAbove;
    }

    @AssertTrue(message = "max-retry-after는 retry-after보다 짧을 수 없다")
    public boolean isMaxRetryAfterNotBelowRetryAfter() {
        return retryAfter == null || maxRetryAfter == null || maxRetryAfter.compareTo(retryAfter) >= 0;
    }
}
