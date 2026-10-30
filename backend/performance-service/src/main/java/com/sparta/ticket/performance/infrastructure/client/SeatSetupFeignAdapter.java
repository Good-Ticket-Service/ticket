package com.sparta.ticket.performance.infrastructure.client;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.port.SeatSetupClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SeatSetupClient의 Feign 구현. performance.seat-setup.client가 없거나 feign이면 쓴다.
 * Note: 응답을 받은 4xx는 공통 FeignErrorDecoder가 좌석의 오류 코드를 담은 BusinessException으로, 5xx는 503
 * SERVICE_UNAVAILABLE로 바꿔 준다. 연결 실패·타임아웃은 RetryableException(FeignException)이다.
 * 400(FAILED)과 409 SEAT_SETUP_STALE_VERSION(무시)만 확정 결과로 보고, 그 밖은 일시 실패로 다시 보낸다.
 * 409 SEAT_SETUP_IN_USE도 일시 실패다. 만료됐지만 정리 전인 HOLD, 결제 중인 좌석은 시간이 지나면 풀리기 때문이다(이슈 #175).
 * 404 등 계약에 없는 응답도 일시 실패로 본다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "performance.seat-setup.client", havingValue = "feign", matchIfMissing = true)
public class SeatSetupFeignAdapter implements SeatSetupClient {

    static final String VALIDATION_REJECTED = "좌석 서비스가 좌석 준비 요청을 거절했습니다(요청 데이터 오류). 운영자에게 문의해 주세요.";
    static final String IN_USE = "이미 선점·판매된 좌석이 있어 좌석 구성을 바꿀 수 없습니다.";

    private final SeatSetupFeignClient client;

    @Override
    public SeatSetupOutcome request(SeatSetupCommand command) {
        SeatSetupResponse response;
        try {
            response = client.setUp(command.scheduleId(), SeatSetupRequest.from(command));
        } catch (BusinessException e) {
            return fromError(e);
        } catch (FeignException e) {
            return new SeatSetupOutcome.Unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (!matches(command, response)) {
            return new SeatSetupOutcome.Unavailable("계약과 다르거나 요청과 맞지 않는 200 응답: " + response);
        }
        return new SeatSetupOutcome.Ready(
                response.requestId(), response.configVersion(), response.seatCount(), response.sellableCount());
    }

    /**
     * 필드가 모두 있고, scheduleId와 좌석 수·판매 대상 수가 보낸 요청과 같은지. requestId·configVersion 비교는 결과 반영 때 한다.
     * Note: 다른 회차의 응답이나 일부 좌석만 만든 응답을 READY로 받으면 판매할 좌석이 없는 회차가 판매 가능해진다(리뷰 R1-2).
     */
    private static boolean matches(SeatSetupCommand command, SeatSetupResponse response) {
        if (response == null
                || response.requestId() == null
                || response.scheduleId() == null
                || response.configVersion() == null
                || response.seatCount() == null
                || response.sellableCount() == null) {
            return false;
        }
        long sellableCount =
                command.seats().stream().filter(SeatSetupCommand.Line::sellable).count();
        return response.scheduleId().equals(command.scheduleId())
                && response.seatCount() == command.seats().size()
                && response.sellableCount() == sellableCount;
    }

    private static SeatSetupOutcome fromError(BusinessException e) {
        int status = e.getErrorCode().status().value();
        String code = e.getErrorCode().code();
        if (status == 400) {
            return new SeatSetupOutcome.Rejected(code, VALIDATION_REJECTED);
        }
        if (status == 409 && "SEAT_SETUP_IN_USE".equals(code)) {
            return new SeatSetupOutcome.Unavailable(code, IN_USE);
        }
        if (status == 409 && "SEAT_SETUP_STALE_VERSION".equals(code)) {
            return new SeatSetupOutcome.Stale();
        }
        return new SeatSetupOutcome.Unavailable(status + " " + code + ": " + e.getMessage());
    }
}
