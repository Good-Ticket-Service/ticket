package com.sparta.ticket.reservation.seat.infrastructure.client;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationPort;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import feign.RetryableException;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 판매 검증 내부 API를 SaleValidationPort로 바꾼다.
 * 4xx(회차 없음 404 SCHEDULE_NOT_FOUND 등)는 공통 FeignErrorDecoder가 performance의 코드를 담은 BusinessException으로,
 * 5xx는 SERVICE_UNAVAILABLE로 바꿔 주므로 그대로 올린다. 연결 실패·타임아웃과 계약과 다른 응답은 여기서 503으로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaleValidationFeignAdapter implements SaleValidationPort {

    private final SaleValidationClient client;

    @Override
    public SaleValidation validate(Long scheduleId, long configVersion, List<Long> venueSeatIds) {
        SaleValidationResponse response;
        try {
            response = client.validate(scheduleId, new SaleValidationRequest(configVersion, venueSeatIds));
        } catch (RetryableException e) {
            log.warn("판매 검증 호출이 응답하지 않았다. scheduleId={}, {}", scheduleId, e.getMessage());
            throw unavailable();
        }
        List<SaleValidationSeat> seats = response.seats() == null
                ? List.of()
                : response.seats().stream()
                        .map(seat -> new SaleValidationSeat(
                                seat.venueSeatId(), seat.venueSeatGradeId(), seat.gradeName(), seat.basePrice()))
                        .toList();
        String violation = contractViolation(response, seats, venueSeatIds);
        if (violation != null) {
            // Note: 사유 없는 거절이나 요청과 다른 좌석을 그대로 넘기면 선점이 엉뚱한 오류를 내거나 다른 좌석 가격으로 예약한다.
            log.error(
                    "판매 검증 응답이 계약과 다르다. {}. scheduleId={}, venueSeatIds={}, response={}",
                    violation,
                    scheduleId,
                    venueSeatIds,
                    response);
            throw unavailable();
        }
        return new SaleValidation(
                response.allowed(),
                response.reasonCode(),
                response.scheduleId(),
                response.performanceId(),
                response.sellerId(),
                response.configVersion(),
                response.policyVersion(),
                seats);
    }

    /** 계약을 어긴 응답이면 그 이유, 아니면 null. */
    private static String contractViolation(
            SaleValidationResponse response, List<SaleValidationSeat> seats, List<Long> venueSeatIds) {
        if (response.allowed() == null) {
            return "allowed가 없다";
        }
        if (!response.allowed()) {
            return response.reasonCode() == null || response.reasonCode().isBlank() ? "거절인데 reasonCode가 없다" : null;
        }
        // 결제 준비에 꼭 넘겨야 하는 값이다(#86)
        if (response.performanceId() == null || response.sellerId() == null) {
            return "허용인데 performanceId·sellerId가 없다";
        }
        List<Long> answered =
                seats.stream().map(SaleValidationSeat::venueSeatId).toList();
        if (answered.size() != new HashSet<>(answered).size()
                || !new HashSet<>(answered).equals(new HashSet<>(venueSeatIds))) {
            return "허용인데 seats가 요청 좌석과 다르다";
        }
        return null;
    }

    private static BusinessException unavailable() {
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "판매 정보를 확인하지 못했습니다. 다시 시도해 주세요.");
    }
}
