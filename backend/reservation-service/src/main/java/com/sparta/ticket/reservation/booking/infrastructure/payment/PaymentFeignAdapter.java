package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.port.PaymentPort;
import com.sparta.ticket.reservation.booking.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.booking.application.port.PreparedPayment;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * payment 내부 API를 PaymentPort로 바꾼다. payment의 상태 이름과 오류 코드는 이 클래스 밖으로 나가지 않는다.
 * 4xx 오류는 공통 FeignErrorDecoder가 payment의 코드를 담은 BusinessException으로 바꿔 준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFeignAdapter implements PaymentPort {

    private static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    private static final String PAYMENT_AMOUNT_MISMATCH = "PAYMENT_AMOUNT_MISMATCH";

    private final PaymentCommandClient commandClient;
    private final PaymentQueryClient queryClient;

    @Override
    public PreparedPayment prepare(PaymentPrepareRequest request) {
        PaymentCheckoutResponse response;
        try {
            response = commandClient.checkout(PaymentCheckoutRequest.from(request));
        } catch (RetryableException e) {
            throw unavailable(e);
        }
        if (!"READY".equals(response.status())) {
            log.warn(
                    "결제 준비가 거절됐다. orderId={}, status={}, failureType={}, failureCode={}",
                    request.orderId(),
                    response.status(),
                    response.failureType(),
                    response.failureCode());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "결제를 준비하지 못했습니다. 다시 시도해 주세요.");
        }
        return new PreparedPayment(response.amount(), response.checkoutUrl());
    }

    @Override
    public ConfirmOutcome confirm(String orderId, String pgAuthToken, long amount) {
        PaymentConfirmResponse response;
        try {
            response = commandClient.confirm(orderId, new PaymentConfirmRequest(pgAuthToken, Math.toIntExact(amount)));
        } catch (BusinessException e) {
            if (PAYMENT_AMOUNT_MISMATCH.equals(e.getErrorCode().code())) {
                // Note: payment는 금액이 다르면 PG를 부르지 않는다. 돈이 오가지 않았으므로 실패로 끝낸다.
                // 결과 모름으로 두면 결과 확인 스케줄러가 같은 금액으로 다시 보내 같은 거절을 끝없이 받는다.
                log.error("승인 금액이 결제 준비 금액과 다르다. orderId={}, amount={}", orderId, amount);
                return ConfirmOutcome.failed();
            }
            throw e;
        }
        return switch (response.status()) {
            case "DONE" -> ConfirmOutcome.succeeded();
            case "FAILED" -> ConfirmOutcome.failed();
            case "IN_PROGRESS" -> ConfirmOutcome.unknown();
            default -> {
                log.error("알 수 없는 승인 결과다. orderId={}, status={}", orderId, response.status());
                yield ConfirmOutcome.unknown();
            }
        };
    }

    @Override
    public PaymentLookup find(String orderId) {
        PaymentDetailResponse response;
        try {
            response = queryClient.find(orderId);
        } catch (BusinessException e) {
            if (PAYMENT_NOT_FOUND.equals(e.getErrorCode().code())) {
                return PaymentLookup.of(PaymentLookup.Status.UNKNOWN_ORDER);
            }
            throw e;
        }
        return switch (response.status()) {
            case "READY" -> PaymentLookup.of(PaymentLookup.Status.NOT_RECEIVED);
            case "IN_PROGRESS" -> PaymentLookup.of(PaymentLookup.Status.IN_PROGRESS);
            case "DONE" -> PaymentLookup.of(PaymentLookup.Status.SUCCEEDED);
            case "FAILED" -> PaymentLookup.of(PaymentLookup.Status.FAILED);
            // Note: 승인된 뒤 전액 환불된 결제다. 돈이 사용자에게 돌아갔으므로 예약에는 실패로 반영한다.
            case "CANCELED" -> PaymentLookup.of(PaymentLookup.Status.FAILED);
            default ->
                throw new IllegalStateException("알 수 없는 결제 상태다. orderId=" + orderId + ", status=" + response.status());
        };
    }

    private static BusinessException unavailable(RetryableException cause) {
        log.warn("payment 호출이 응답하지 않았다. {}", cause.getMessage());
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "결제 서비스가 응답하지 않습니다. 다시 시도해 주세요.");
    }
}
