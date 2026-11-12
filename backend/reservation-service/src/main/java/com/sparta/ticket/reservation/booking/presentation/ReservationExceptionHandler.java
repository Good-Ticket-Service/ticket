package com.sparta.ticket.reservation.booking.presentation;

import com.sparta.ticket.common.exception.ErrorResponse;
import com.sparta.ticket.reservation.booking.application.payment.AdmissionRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예약 API에서 공통 형식에 헤더를 더해야 하는 오류만 처리한다. 공통 처리기는 BusinessException에 헤더를 붙이지 않는데, 결제 준비 거절은
 * 언제 다시 올지(Retry-After)를 알려야 클라이언트가 바로 몰려오지 않는다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReservationController.class)
class ReservationExceptionHandler {

    @ExceptionHandler(AdmissionRejectedException.class)
    ResponseEntity<ErrorResponse> handleAdmissionRejected(AdmissionRejectedException e, HttpServletRequest request) {
        return ResponseEntity.status(e.getErrorCode().status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfter().toSeconds()))
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage(), request.getRequestURI()));
    }
}
