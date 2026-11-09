package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.application.port.SelectableSeatCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 좌석 배치도 조회(GET /api/seats/schedules/{scheduleId}와 /layout·/status)의 진입점. 회차별 판정 결과를 서버 메모리에
 * 짧게 둔다(#270). 세 API가 같은 스냅샷을 쓴다(#271). 만든 지 cache-refresh(1초)가 지나면 기존 스냅샷을 주면서 뒤에서 다시 만들고,
 * cache-max-age(5초)가 지나면 만료한다(#311).
 *
 * 이유: SelectableSeatQueryService는 회차 전체 좌석 조회·판정·결제 중 여부 조회 동안 DB 연결을 잡는다. 같은 회차 요청이 몰리면
 * 배치도 요청이 커넥션 풀을 차지해 같은 서비스의 선점·결제 API가 연결을 기다린다. 스냅샷을 두면 회차당 DB 조회는 refresh마다 한 번이다.
 * 만료 순간 요청 스레드가 로딩을 기다리면 같은 회차 요청이 모두 회차 키 잠금에 묶이므로, 다시 만드는 일은 전용 스레드가 한다(#311).
 *
 * 정합성: 배치도는 화면 표시용이고, 실제로 선점할 수 있는지는 선점 API가 좌석을 잠근 뒤 다시 판정한다(만료 HOLD는 expireIfDue
 * 조건부 변경). 그래서 늦은 스냅샷은 409 SEAT_ALREADY_TAKEN이 늘어나는 것으로만 나타나고 이중 선점은 생기지 않는다.
 * 선점·해제 때 캐시를 지우지 않는다. 좌석 상태를 바꾸는 경로가 여럿이라(잔여석 캐시와 같은 이유) 시간이 지나면 다시 만든다.
 * 404 SEAT_NOT_FOUND 같은 예외는 캐시하지 않아 좌석 준비 직후 바로 보인다.
 *
 * Note: 이 클래스에는 트랜잭션을 두지 않는다. 메서드에 트랜잭션이 있으면 캐시에 있어도 진입만으로 DB 연결을 잡아 효과가 없다.
 * 스냅샷을 만들 때만 SeatMapSnapshotLoader가 부르는 SelectableSeatQueryService가 읽기 전용 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class SelectableSeatSnapshotService {

    private final SelectableSeatCache cache;
    private final SeatSnapshotMetrics metrics;

    /** 기존 좌석 조회 응답용 판정 결과. */
    public SelectableSeatsResult getSelectableSeats(Long scheduleId) {
        return getSnapshot(scheduleId).result();
    }

    /** layout·status 응답용 스냅샷. 좌석이 없는 회차는 404 SEAT_NOT_FOUND다(캐시하지 않음). */
    public SeatMapSnapshot getSnapshot(Long scheduleId) {
        return metrics.timeGet(() -> cache.get(scheduleId));
    }
}
