package com.sparta.ticket.reservation.booking.application.schedulecancel;

import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.domain.ScheduleCancellationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 취소를 받아 기록만 남긴다. 회차 하나에 예약이 수천 건일 수 있어 수신 트랜잭션에서 처리하지 않는다.
 * 기록이 커밋되면 재시작해도 예약 정리를 이어 갈 수 있으므로, 호출한 쪽은 이 메서드가 끝난 뒤에 메시지를 끝낸 것으로 친다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleCancellationReceiver {

    private final ScheduleCancellationRepository repository;
    private final ChangeStamps changeStamps;

    @Transactional
    public void receive(ScheduleCanceled event) {
        int inserted =
                repository.recordIfAbsent(event.scheduleId(), event.eventId(), event.canceledAt(), changeStamps.now());
        if (inserted == 0) {
            log.info("이미 받은 회차 취소다. scheduleId={}, eventId={}", event.scheduleId(), event.eventId());
        }
    }
}
