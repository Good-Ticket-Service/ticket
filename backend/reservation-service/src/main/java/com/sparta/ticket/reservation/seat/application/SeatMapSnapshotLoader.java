package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회차 좌석 배치도 스냅샷을 DB에서 새로 만든다. 스냅샷 캐시(SelectableSeatCache 구현)가 처음 로딩과 refresh에 쓴다(#311).
 * 처음 로딩·만료 뒤 로딩은 요청 스레드에서, refresh는 캐시 전용 스레드에서 실행된다. 어느 쪽이든 load 지표에 남는다.
 *
 * Note: 이 클래스에는 트랜잭션을 두지 않는다. 읽기 전용 트랜잭션은 SelectableSeatQueryService가 열고, 비트맵은 그 트랜잭션이 끝난 뒤 계산한다.
 */
@Component
@RequiredArgsConstructor
public class SeatMapSnapshotLoader {

    private final SelectableSeatQueryService queryService;
    private final SeatSnapshotMetrics metrics;

    /** 좌석이 없는 회차는 404 SEAT_NOT_FOUND를 던진다. 캐시는 예외를 저장하지 않는다. */
    public SeatMapSnapshot load(Long scheduleId) {
        return metrics.timeLoad(() -> SeatMapSnapshot.of(queryService.getSelectableSeats(scheduleId)));
    }
}
