package com.sparta.ticket.reservation.booking.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.reservation.booking.application.cancel.ReservationCancelService;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.query.PaymentPollingInterval;
import com.sparta.ticket.reservation.booking.application.query.ReservationQueryService;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationStatusView;
import com.sparta.ticket.reservation.booking.presentation.dto.CancelRequest;
import com.sparta.ticket.reservation.booking.presentation.dto.CheckoutRequest;
import com.sparta.ticket.reservation.booking.presentation.dto.CheckoutResponse;
import com.sparta.ticket.reservation.booking.presentation.dto.ConfirmRequest;
import com.sparta.ticket.reservation.booking.presentation.dto.ReservationResponse;
import com.sparta.ticket.reservation.booking.presentation.dto.ReservationStatusResponse;
import com.sparta.ticket.reservation.booking.presentation.dto.ReservationSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "예약")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationQueryService reservationQueryService;
    private final ReservationPaymentService reservationPaymentService;
    private final ReservationCancelService reservationCancelService;
    private final PaymentPollingInterval pollingInterval;

    @Operation(summary = "내 예매 목록", description = "최신순이다. 상태를 고르지 않으면 결제 없이 끝난 EXPIRED·RELEASED는 빼고 보여 준다.")
    @RequireRole(Role.USER)
    @GetMapping
    public ApiResponse<PageResponse<ReservationSummaryResponse>> findMine(
            @CurrentUser UserContext user,
            @RequestParam(required = false) ReservationStatus status,
            Pageable pageable) {
        return ApiResponse.ok(PageResponse.from(
                reservationQueryService.findMine(user.userId(), status, pageable), ReservationSummaryResponse::from));
    }

    /** 결제 중이면 다음 조회까지 기다릴 초를 Retry-After로 준다. 헤더를 붙여야 해서 다른 API와 달리 ResponseEntity로 돌려준다. */
    @Operation(summary = "예약 단건 조회", description = "결제 중이면 Retry-After 헤더로 다음 조회까지 기다릴 초를 준다.")
    @GetMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<ReservationResponse>> get(
            @CurrentUser UserContext user, @PathVariable Long reservationId) {
        ReservationResult result = reservationQueryService.get(user, reservationId);
        return polling(result.status()).body(ApiResponse.ok(ReservationResponse.from(result)));
    }

    /**
     * 결제 결과를 기다리는 폴링용. 상태만 읽어 단건 조회보다 가볍고, 결과가 정해지면 Retry-After가 없어 클라이언트가 멈춘다.
     * 응답이 캐시되면 요청이 서버에 오지 않아 서버가 정한 간격이 무의미해지므로 no-store를 붙인다.
     */
    @Operation(
            summary = "예약 상태 조회",
            description = "결제 승인 뒤 PAYMENT_IN_PROGRESS를 받았으면 이 API로 폴링한다. Retry-After가 없으면 결과가 정해진 것이다.")
    @GetMapping("/{reservationId}/status")
    public ResponseEntity<ApiResponse<ReservationStatusResponse>> getStatus(
            @CurrentUser UserContext user, @PathVariable Long reservationId) {
        ReservationStatusView view = reservationQueryService.getStatus(user, reservationId);
        return polling(view.status())
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(ReservationStatusResponse.from(view)));
    }

    private ResponseEntity.BodyBuilder polling(ReservationStatus status) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        pollingInterval
                .forStatus(status)
                .ifPresent(interval -> response.header(HttpHeaders.RETRY_AFTER, String.valueOf(interval.toSeconds())));
        return response;
    }

    @Operation(summary = "결제 준비", description = "새 orderId와 쿠폰을 적용한 결제 금액을 받는다. 예약은 HOLD 그대로다.")
    @PostMapping("/{reservationId}/payments/checkout")
    public ApiResponse<CheckoutResponse> checkout(
            @CurrentUser UserContext user,
            @PathVariable Long reservationId,
            @Valid @RequestBody CheckoutRequest request) {
        return ApiResponse.ok(
                CheckoutResponse.from(reservationPaymentService.checkout(user, reservationId, request.toCommand())));
    }

    @Operation(
            summary = "결제 승인",
            description = "결제창 인증 뒤 부른다. 응답은 예약 상태다. 결과를 모르면 PAYMENT_IN_PROGRESS이고, 조회 API로 폴링한다.")
    @PostMapping("/{reservationId}/payments/confirm")
    public ApiResponse<ReservationResponse> confirm(
            @CurrentUser UserContext user,
            @PathVariable Long reservationId,
            @Valid @RequestBody ConfirmRequest request) {
        return ApiResponse.ok(
                ReservationResponse.from(reservationPaymentService.confirm(user, reservationId, request.toCommand())));
    }

    @Operation(
            summary = "예약 취소",
            description = "확정한 예약을 회차 시작 전에 취소한다. 응답은 예약 상태다. 환불 요청 결과를 모르면 CANCELING이고, 조회 API로 확인한다.")
    @PostMapping("/{reservationId}/cancel")
    public ApiResponse<ReservationResponse> cancel(
            @CurrentUser UserContext user,
            @PathVariable Long reservationId,
            @Valid @RequestBody(required = false) CancelRequest request) {
        return ApiResponse.ok(ReservationResponse.from(reservationCancelService.cancel(user, reservationId)));
    }
}
