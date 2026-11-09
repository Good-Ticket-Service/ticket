package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.domain.PaymentResultEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment가 보낸 결제 결과를 예약에 반영한다. 반영 규칙은 승인 응답·결과 확인과 같은 PaymentResultApplier다.
 * 수신 기록과 반영을 한 트랜잭션으로 묶어, 둘 다 커밋된 뒤에만 부르는 쪽이 메시지를 끝낸 것으로 친다.
 * 반영이 실패하면 기록도 남지 않아 같은 메시지를 다시 받았을 때 처음부터 다시 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResultReceiver {

    private final PaymentResultEventRepository events;
    private final PaymentResultApplier resultApplier;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** 예약 상태가 바뀌었으면 true. 같은 메시지가 다시 왔거나, 같은 결과를 이미 반영했거나, 지난 시도의 결과면 false. */
    @Transactional
    public boolean receive(PaymentResult result) {
        // Note: 결과 반영은 orderId 조건부 UPDATE라 같은 결과가 다시 와도 바뀌지 않는다. 그래도 같은 메시지는 여기서 거른다.
        // 반영 전의 판단(지난 시도의 성공 기록, 지표)까지 한 번만 하고, 받은 시각을 남겨 결과 전달이 늦은 정도를 재기 위해서다.
        int recorded = events.recordIfAbsent(
                result.eventId(), result.orderId(), result.reservationId(), result.occurredAt(), changeStamps.now());
        if (recorded == 0) {
            log.info("이미 받은 결제 결과다. eventId={}, orderId={}", result.eventId(), result.orderId());
            return false;
        }
        boolean changed = resultApplier.apply(result.reservationId(), result.orderId(), result.outcome());
        if (changed) {
            metrics.paymentResultApplied();
        }
        return changed;
    }
}
