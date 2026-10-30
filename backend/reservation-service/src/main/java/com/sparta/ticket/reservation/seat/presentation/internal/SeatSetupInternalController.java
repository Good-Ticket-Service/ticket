package com.sparta.ticket.reservation.seat.presentation.internal;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.seat.application.setup.SeatSetupService;
import com.sparta.ticket.reservation.seat.presentation.internal.dto.SeatSetupRequest;
import com.sparta.ticket.reservation.seat.presentation.internal.dto.SeatSetupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 호출 전용 회차 좌석 준비 API(공연 서비스 → 좌석, #175). Gateway 라우트가 /api/**만 받으므로 외부에서 호출할 수 없다.
 * Note: 내부 API는 서비스끼리의 계약이라 DTO를 ApiResponse로 감싸지 않고 그대로 반환한다. 실패는 공통 에러 형식이라
 * 호출하는 쪽 FeignErrorDecoder가 code를 그대로 읽는다.
 * 잠금 대기 상한을 넘기면 503 SERVICE_UNAVAILABLE이다. 공연 쪽은 5xx를 같은 requestId로 재시도하고, 같은 버전은 멱등이라 결과가 같다.
 */
@Slf4j
@Tag(name = "좌석 내부")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/schedules/{scheduleId}/seat-setups")
public class SeatSetupInternalController {

    private final SeatSetupService seatSetupService;

    @Operation(summary = "회차 좌석 준비", description = "회차 좌석 전체를 받아 만들거나 새 구성으로 바꾼다. 같은 구성 버전의 재요청은 저장된 결과를 다시 준다.")
    @PostMapping
    public SeatSetupResponse setup(@PathVariable Long scheduleId, @Valid @RequestBody SeatSetupRequest request) {
        if (!scheduleId.equals(request.scheduleId())) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "경로와 본문의 scheduleId가 같아야 합니다.");
        }
        try {
            return SeatSetupResponse.from(seatSetupService.setup(request.toCommand()));
        } catch (PessimisticLockingFailureException e) {
            // 같은 회차의 다른 준비나 선점이 잠금을 오래 쥐어 대기 상한을 넘겼다. 일시적인 상태라 503으로 알려
            // 공연 쪽이 같은 requestId로 다시 보내게 한다(5xx는 재시도, #175). 트랜잭션은 이미 롤백돼 남은 변경이 없다
            log.warn("좌석 준비가 잠금을 기다리다 끝났다. scheduleId={}, configVersion={}", scheduleId, request.configVersion());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "좌석 준비가 다른 작업과 겹쳤습니다. 잠시 뒤 다시 보내 주세요.");
        }
    }
}
