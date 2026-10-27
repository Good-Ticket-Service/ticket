package com.sparta.ticket.payment.presentation.dto.internal;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * POST /internal/payments 요청(docs/shared/internal-api/payment.md). 할인액은 음수도 받아 둔다. 음수·초과 할인은 입력 형식
 * 오류(VALIDATION_ERROR)가 아니라 금액 규칙 위반(INVALID_PAYMENT_AMOUNT)으로 계약에 정해져 있어 도메인이 판단한다.
 */
public record CheckoutRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotNull Long reservationId,
        @NotNull Long userId,
        @NotNull Long sellerId,
        @NotNull Long performanceId,
        @NotNull Long scheduleId,
        @NotNull @Positive Integer totalPrice,
        @NotNull Integer discountAmount,
        @NotNull PgProvider pgProvider,
        @NotNull OffsetDateTime expiresAt) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** DB는 KST LocalDateTime이다. 다른 오프셋으로 와도 같은 순간의 한국 시각으로 바꾼다. */
    public CheckoutCommand toCommand() {
        return new CheckoutCommand(
                orderId,
                reservationId,
                userId,
                sellerId,
                performanceId,
                scheduleId,
                pgProvider,
                totalPrice,
                discountAmount,
                expiresAt.atZoneSameInstant(SEOUL).toLocalDateTime());
    }
}
