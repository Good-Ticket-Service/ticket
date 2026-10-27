package com.sparta.ticket.fakepg.presentation.controller.kakao;

import com.sparta.ticket.fakepg.application.service.kakao.KakaoPaymentService;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoApproveRequest;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoCancelRequest;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoOrderRequest;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoPaymentResponse;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoReadyRequest;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoReadyResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카카오페이 단건 결제 API 호환 경로. 모든 API가 {@code Authorization: SECRET_KEY {시크릿 키}}를 요구하고 JSON 본문을 받는다.
 */
@RestController
@RequestMapping("/online/v1/payment")
public class KakaoPaymentController {

    private final KakaoPaymentService paymentService;
    private final KakaoApiExecutor executor;
    private final String checkoutUrlBase;

    public KakaoPaymentController(
            KakaoPaymentService paymentService,
            KakaoApiExecutor executor,
            @Value("${fake-pg.kakao.checkout-url-base}") String checkoutUrlBase) {
        this.paymentService = paymentService;
        this.executor = executor;
        this.checkoutUrlBase = checkoutUrlBase;
    }

    @PostMapping("/ready")
    public ResponseEntity<Object> ready(@Valid @RequestBody KakaoReadyRequest request) {
        return executor.execute(
                PgOperation.READY,
                () -> KakaoReadyResponse.from(
                        paymentService.ready(
                                request.cid(),
                                request.partnerOrderId(),
                                request.partnerUserId(),
                                request.itemName(),
                                request.totalAmount()),
                        checkoutUrlBase));
    }

    @PostMapping("/approve")
    public ResponseEntity<Object> approve(@Valid @RequestBody KakaoApproveRequest request) {
        return executor.execute(
                PgOperation.CONFIRM,
                () -> KakaoPaymentResponse.from(
                        paymentService.approve(
                                request.cid(),
                                request.tid(),
                                request.partnerOrderId(),
                                request.partnerUserId(),
                                request.pgToken()),
                        request.cid()));
    }

    @PostMapping("/order")
    public ResponseEntity<Object> order(@Valid @RequestBody KakaoOrderRequest request) {
        return executor.execute(
                PgOperation.INQUIRE,
                () -> KakaoPaymentResponse.from(paymentService.findByTid(request.cid(), request.tid()), request.cid()));
    }

    @PostMapping("/cancel")
    public ResponseEntity<Object> cancel(@Valid @RequestBody KakaoCancelRequest request) {
        return executor.execute(
                PgOperation.CANCEL,
                () -> KakaoPaymentResponse.from(
                        paymentService.cancel(request.cid(), request.tid(), request.cancelAmount()), request.cid()));
    }
}
