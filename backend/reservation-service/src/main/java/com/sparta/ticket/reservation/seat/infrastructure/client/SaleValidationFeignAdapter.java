package com.sparta.ticket.reservation.seat.infrastructure.client;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationPort;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import feign.RetryableException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 판매 검증 내부 API를 SaleValidationPort로 바꾼다.
 * 4xx(회차 없음 404 SCHEDULE_NOT_FOUND 등)는 공통 FeignErrorDecoder가 performance의 코드를 담은 BusinessException으로,
 * 5xx는 SERVICE_UNAVAILABLE로 바꿔 주므로 그대로 올린다. 연결 실패·타임아웃만 여기서 503으로 바꾼다.
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
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "판매 정보를 확인하지 못했습니다. 다시 시도해 주세요.");
        }
        List<SaleValidationSeat> seats = response.seats() == null
                ? List.of()
                : response.seats().stream()
                        .map(seat -> new SaleValidationSeat(
                                seat.venueSeatId(), seat.venueSeatGradeId(), seat.gradeName(), seat.basePrice()))
                        .toList();
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
}
