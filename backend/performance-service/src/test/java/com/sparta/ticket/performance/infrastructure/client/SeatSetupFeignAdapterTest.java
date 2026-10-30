package com.sparta.ticket.performance.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.exception.SimpleErrorCode;
import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import feign.Request;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 좌석 준비 Feign 응답을 결과 네 가지로 바꾸는지 확인한다. 4xx·5xx를 BusinessException으로 바꾸는 일은 공통 FeignErrorDecoder가
 * 하므로, 여기서는 그 결과(BusinessException, RetryableException)를 받는다고 가정한다.
 */
class SeatSetupFeignAdapterTest {

    private static final SeatSetupCommand COMMAND = new SeatSetupCommand(
            "req-1", 10L, 2, List.of(new SeatSetupCommand.Line(1L, 1L, "VIP", 150000, true, "A", "1", 1)));

    private final SeatSetupFeignClient client = mock(SeatSetupFeignClient.class);
    private final SeatSetupFeignAdapter adapter = new SeatSetupFeignAdapter(client);

    private void respond(SeatSetupResponse response) {
        when(client.setUp(eq(10L), any())).thenReturn(response);
    }

    private void fail(RuntimeException exception) {
        when(client.setUp(eq(10L), any())).thenThrow(exception);
    }

    private static BusinessException seatError(HttpStatus status, String code) {
        return new BusinessException(new SimpleErrorCode(status, code, "좌석 오류"));
    }

    @Test
    @DisplayName("계약 필드 이름으로 요청을 만든다")
    void buildsRequest() {
        SeatSetupRequest request = SeatSetupRequest.from(COMMAND);

        assertThat(request.requestId()).isEqualTo("req-1");
        assertThat(request.scheduleId()).isEqualTo(10L);
        assertThat(request.configVersion()).isEqualTo(2);
        assertThat(request.seats()).singleElement().satisfies(seat -> {
            assertThat(seat.isSellable()).isTrue();
            assertThat(seat.basePrice()).isEqualTo(150000);
        });
    }

    @Test
    @DisplayName("200이면 Ready")
    void ready() {
        respond(new SeatSetupResponse("req-1", 10L, 2L, 1, 1));

        assertThat(adapter.request(COMMAND)).isEqualTo(new SeatSetupOutcome.Ready("req-1", 2, 1, 1));
    }

    @Test
    @DisplayName("200인데 계약 필드가 빠지면 일시 실패")
    void incompleteResponse() {
        respond(new SeatSetupResponse(null, 10L, 2L, 1, 1));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);

        respond(new SeatSetupResponse("req-1", null, 2L, 1, 1));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);
    }

    @Test
    @DisplayName("200인데 scheduleId나 좌석 수·판매 대상 수가 요청과 다르면 일시 실패")
    void mismatchedResponse() {
        respond(new SeatSetupResponse("req-1", 11L, 2L, 1, 1));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);

        respond(new SeatSetupResponse("req-1", 10L, 2L, 2, 1));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);

        respond(new SeatSetupResponse("req-1", 10L, 2L, 1, 0));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);
    }

    @Test
    @DisplayName("400은 거절(FAILED), 409 SEAT_SETUP_STALE_VERSION은 무시")
    void definitiveErrors() {
        fail(seatError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
        assertThat(adapter.request(COMMAND))
                .isEqualTo(
                        new SeatSetupOutcome.Rejected("VALIDATION_ERROR", SeatSetupFeignAdapter.VALIDATION_REJECTED));

        fail(seatError(HttpStatus.CONFLICT, "SEAT_SETUP_STALE_VERSION"));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Stale.class);
    }

    @Test
    @DisplayName("409 SEAT_SETUP_IN_USE는 시간이 지나면 풀릴 수 있어 일시 실패이고, 끝내 실패할 때의 사유를 담는다")
    void inUseIsTransient() {
        fail(seatError(HttpStatus.CONFLICT, "SEAT_SETUP_IN_USE"));

        assertThat(adapter.request(COMMAND))
                .isEqualTo(new SeatSetupOutcome.Unavailable("SEAT_SETUP_IN_USE", SeatSetupFeignAdapter.IN_USE));
    }

    @Test
    @DisplayName("5xx(공통 디코더가 503으로 바꿈), 계약에 없는 4xx, 연결 실패·타임아웃은 일시 실패")
    void transientErrors() {
        fail(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);

        fail(seatError(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);

        fail(seatError(HttpStatus.CONFLICT, "OTHER_CONFLICT"));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);

        Request request = Request.create(
                Request.HttpMethod.POST,
                "/internal/schedules/10/seat-setups",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
        fail(new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request));
        assertThat(adapter.request(COMMAND)).isInstanceOf(SeatSetupOutcome.Unavailable.class);
    }
}
