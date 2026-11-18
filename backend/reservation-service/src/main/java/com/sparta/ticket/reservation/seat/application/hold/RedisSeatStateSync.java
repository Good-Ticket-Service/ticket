package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DB가 좌석을 풀 때 Redis 점유 좌석에서도 지운다(#459). 좌석을 AVAILABLE로 돌리는 세 곳이 부른다.
 *   - SeatFacadeService.release: booking의 만료 스케줄러·취소·결제 실패
 *   - SeatReleaseService: 회원이 DB 예약을 해제
 *   - SeatHoldTransactions: 선점·결제하기가 다른 예약의 만료 선점을 정리
 *
 * 지우는 때는 트랜잭션 커밋 뒤다. 커밋 전에 지우면 롤백됐을 때 DB는 잡혀 있는데 Redis는 비었다고 본다.
 * Redis가 실패해도 예외를 던지지 않는다. 이미 커밋한 만료·취소를 실패로 만들 수 없고, 남은 값은 정합 작업(RedisSeatStateReconciler)이 지운다.
 * 남은 값은 좌석을 못 파는 쪽으로만 틀리고 이중 판매로 이어지지 않는다.
 *
 * Note: DB 모드에서는 아무것도 하지 않는다. DB 모드가 Redis 장애 때의 대체 경로라 Redis를 부르지 않기 위해서다.
 * 그래서 DB 모드 동안 풀린 좌석은 Redis에 남는다. REDIS 모드로 되돌릴 때는 회차를 다시 올린다(RedisSeatStateLoader.reload).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSeatStateSync {

    private final SeatHoldStore store;
    private final SeatHoldModeSwitch modeSwitch;

    /** 이 예약이 잡고 있던 좌석을 커밋 뒤 Redis에서 지운다. 다른 예약의 좌석은 값이 달라 지워지지 않는다. */
    public void releasedAfterCommit(Collection<ScheduleSeat> seats, Long reservationId) {
        if (modeSwitch.current() != SeatHoldMode.REDIS || seats.isEmpty()) {
            return;
        }
        Map<Long, Map<Long, Long>> bySchedule = new LinkedHashMap<>();
        for (ScheduleSeat seat : seats) {
            bySchedule
                    .computeIfAbsent(seat.getScheduleId(), id -> new LinkedHashMap<>())
                    .put(seat.getId(), reservationId);
        }
        afterCommit(() -> bySchedule.forEach(this::free));
    }

    private void free(Long scheduleId, Map<Long, Long> seats) {
        try {
            store.freeReserved(scheduleId, seats);
        } catch (BusinessException e) {
            log.warn("DB에서 풀린 좌석을 Redis에서 지우지 못했다. 정합 작업이 지운다. scheduleId={}, seats={}", scheduleId, seats);
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
