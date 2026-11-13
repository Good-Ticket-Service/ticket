package com.sparta.ticket.reservation.booking.application.port;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Outbox에 남아 브로커가 받지 않은 승인 요청 중 HTTP로 대신 보낼 것을 고른다. 고를 때 예약에 보낸 시각을 남기고, 그 시각이
 * resendAfter보다 오래되기 전에는 어느 인스턴스의 작업자도 같은 요청을 다시 고르지 않는다. 메서드마다 트랜잭션을 열고 닫아
 * 돌아온 뒤에는 DB 연결을 쥐지 않는다. Outbox 행은 읽기만 하고 잠그거나 지우지 않는다.
 */
public interface StrandedConfirms {

    /**
     * 승인 시한이 가장 이른 요청 하나를 골라 보낸 시각을 now로 남긴다. 결제 중이고, 시한이 now 뒤이고, Outbox 행이 예약의 지금
     * 시도이고, 마지막으로 보낸 시각(없으면 접수 시각)이 now - resendAfter 이전인 요청만 고른다.
     */
    Optional<ClaimedConfirm> claimNext(LocalDateTime now, Duration resendAfter);

    /** 보낸 시각을 지워 바로 다시 고를 수 있게 한다. 다시 고르면 보내지 않은 요청으로 본다. */
    void requeue(PaymentConfirmRequest request);

    /** 시한이 지날 때까지 다시 고르지 않는다. 시한 뒤에는 결과 확인이 지금처럼 끝낸다. */
    void parkUntilDeadline(PaymentConfirmRequest request);

    /**
     * 고른 요청. sent는 고르기 전에 보낸 시각이 이미 있었는지다. 시각은 승인을 보냈고 payment가 자리가 없다며 돌려보내지 않았을 때
     * 남아 있으므로, 있으면 payment가 그 승인을 받았을 수 있다.
     */
    record ClaimedConfirm(PaymentConfirmRequest request, boolean sent) {}
}
