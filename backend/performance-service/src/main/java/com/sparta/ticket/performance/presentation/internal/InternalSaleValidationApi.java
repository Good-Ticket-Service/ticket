package com.sparta.ticket.performance.presentation.internal;

import com.sparta.ticket.performance.application.service.SaleValidationService;
import com.sparta.ticket.performance.presentation.internal.dto.SaleValidationRequest;
import com.sparta.ticket.performance.presentation.internal.dto.SaleValidationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 호출 전용 판매 검증 API. Gateway에 라우트가 없어 외부에서 호출할 수 없다.
 * 경로·HTTP 메서드만 여기 두고, Swagger 문서와 파라미터 바인딩·검증은 {@link InternalSaleValidationApiSpec}에 있다.
 * Note: 내부 API는 서비스끼리의 계약이라 DTO를 ApiResponse로 감싸지 않고 그대로 반환한다. 실패는 공통 에러 형식이라
 * 호출하는 쪽 FeignErrorDecoder가 code를 그대로 읽는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/schedules")
public class InternalSaleValidationApi implements InternalSaleValidationApiSpec {

    private final SaleValidationService saleValidationService;

    /** 회차를 지금 팔 수 있는지와 요청 좌석의 등급·가격을 준다. 업무 거절도 200이다. */
    @Override
    @PostMapping("/{scheduleId}/sale-validations")
    public SaleValidationResponse validate(Long scheduleId, SaleValidationRequest request) {
        return SaleValidationResponse.from(
                saleValidationService.validate(scheduleId, request.configVersion(), request.venueSeatIds()));
    }
}
