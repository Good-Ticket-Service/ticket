package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.booking.application.port.StrandedConfirms;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox에 남은 승인 요청을 고른다. 고르기는 한 트랜잭션이다. 예약 행을 잠그며 고르고, 같은 트랜잭션에서 보낸 시각을 남기고
 * 커밋한다. 잠금은 커밋하면 풀리고, 그 뒤 resendAfter 동안은 남긴 시각이 다른 작업자를 막는다. HTTP 호출은 이 밖에서 한다.
 */
@Component
@RequiredArgsConstructor
class OutboxStrandedConfirms implements StrandedConfirms {

    private final OutboxRowRepository outbox;
    private final ReservationRepository reservations;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Optional<ClaimedConfirm> claimNext(LocalDateTime now, Duration resendAfter) {
        return outbox.lockNextStranded(PaymentConfirmRequestedMessage.TOPIC, now, now.minus(resendAfter))
                .map(row -> new ClaimedConfirm(
                        PaymentConfirmRequestedMessage.toRequest(objectMapper, row.getPayload()), row.isSent()))
                .filter(claimed -> reservations.markSubstituteSent(
                                claimed.request().reservationId(),
                                claimed.request().orderId(),
                                now)
                        == 1);
    }

    @Override
    @Transactional
    public void requeue(PaymentConfirmRequest request) {
        reservations.markSubstituteSent(request.reservationId(), request.orderId(), null);
    }

    /** 고르는 조건이 시한 전이고 보낸 시각이 resendAfter보다 오래된 것이라, 보낸 시각을 시한으로 미루면 시한까지 고르지 않는다. */
    @Override
    @Transactional
    public void parkUntilDeadline(PaymentConfirmRequest request) {
        reservations.markSubstituteSent(request.reservationId(), request.orderId(), request.confirmBefore());
    }
}
