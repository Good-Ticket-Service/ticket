package com.sparta.ticket.fakepg.presentation.controller.toss;

import com.sparta.ticket.fakepg.application.service.toss.TossPaymentService;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossCancelRequest;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossConfirmRequest;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossPaymentResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토스페이먼츠 결제 API 호환 경로. 승인·조회·취소 모두 Basic 인증(시크릿 키)이 필요하다.
 */
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class TossPaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final TossPaymentService paymentService;
    private final TossApiExecutor executor;

    @PostMapping("/confirm")
    public ResponseEntity<Object> confirm(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TossConfirmRequest request) {
        return executor.execute(
                PgOperation.CONFIRM,
                idempotencyKey,
                request,
                () -> TossPaymentResponse.from(
                        paymentService.confirm(request.paymentKey(), request.orderId(), request.amount())));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Object> findByOrderId(@PathVariable String orderId) {
        return executor.execute(
                PgOperation.INQUIRE, null, null, () -> TossPaymentResponse.from(paymentService.findByOrderId(orderId)));
    }

    @GetMapping("/{paymentKey}")
    public ResponseEntity<Object> findByPaymentKey(@PathVariable String paymentKey) {
        return executor.execute(
                PgOperation.INQUIRE,
                null,
                null,
                () -> TossPaymentResponse.from(paymentService.findByPaymentKey(paymentKey)));
    }

    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<Object> cancel(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @PathVariable String paymentKey,
            @Valid @RequestBody TossCancelRequest request) {
        return executor.execute(
                PgOperation.CANCEL,
                idempotencyKey,
                Map.of("paymentKey", paymentKey, "request", request),
                () -> TossPaymentResponse.from(
                        paymentService.cancel(paymentKey, request.cancelReason(), request.cancelAmount())));
    }
}
