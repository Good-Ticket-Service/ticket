package com.sparta.ticket.performance.presentation.dto;

import com.sparta.ticket.performance.application.dto.SaleValidationResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 판매 검증 응답(P21). 내부 API라 ApiResponse로 감싸지 않는다. 시각은 +09:00 오프셋을 붙인 ISO 8601이다.
 * 거절이면 allowed=false, reasonCode, 빈 seats이고 나머지 필드는 허용과 똑같이 채운다.
 */
public record SaleValidationResponse(
        boolean allowed,
        String reasonCode,
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        long configVersion,
        long policyVersion,
        String performanceTitle,
        OffsetDateTime scheduleStartAt,
        OffsetDateTime validatedAt,
        List<Seat> seats) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SaleValidationResponse from(SaleValidationResult result) {
        return new SaleValidationResponse(
                result.allowed(),
                result.reasonCode() == null ? null : result.reasonCode().name(),
                result.scheduleId(),
                result.performanceId(),
                result.sellerId(),
                result.configVersion(),
                result.policyVersion(),
                result.performanceTitle(),
                offset(result.scheduleStartAt()),
                offset(result.validatedAt()),
                result.seats().stream().map(Seat::from).toList());
    }

    /** DB의 KST 시각에 +09:00 오프셋을 붙인다. */
    private static OffsetDateTime offset(LocalDateTime time) {
        return time.atZone(SEOUL).toOffsetDateTime();
    }

    public record Seat(Long venueSeatId, Long venueSeatGradeId, String gradeName, int basePrice) {

        private static Seat from(SaleValidationResult.Seat seat) {
            return new Seat(seat.venueSeatId(), seat.venueSeatGradeId(), seat.gradeName(), seat.basePrice());
        }
    }
}
