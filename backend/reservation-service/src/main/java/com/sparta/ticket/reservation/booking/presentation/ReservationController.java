package com.sparta.ticket.reservation.booking.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.query.ReservationQueryService;
import com.sparta.ticket.reservation.booking.presentation.dto.CheckoutRequest;
import com.sparta.ticket.reservation.booking.presentation.dto.CheckoutResponse;
import com.sparta.ticket.reservation.booking.presentation.dto.ConfirmRequest;
import com.sparta.ticket.reservation.booking.presentation.dto.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "예약")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationQueryService reservationQueryService;
    private final ReservationPaymentService reservationPaymentService;

    @Operation(summary = "예약 상태 조회", description = "결제 승인 뒤 PAYMENT_IN_PROGRESS를 받았으면 이 API로 폴링한다.")
    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationResponse> get(@CurrentUser UserContext user, @PathVariable Long reservationId) {
        return ApiResponse.ok(ReservationResponse.from(reservationQueryService.get(user, reservationId)));
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
}
