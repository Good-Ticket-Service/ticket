package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.application.port.SelectableSeatCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 좌석 배치도 조회(GET /api/seats/schedules/{scheduleId})의 진입점. 회차별 판정 결과를 서버 메모리에 짧게(설정값, 1초) 둔다(#270).
 *
 * 이유: SelectableSeatQueryService는 회차 전체 좌석 조회·판정·결제 중 여부 조회 동안 DB 연결을 잡는다. 같은 회차 요청이 몰리면
 * 배치도 요청이 커넥션 풀을 차지해 같은 서비스의 선점·결제 API가 연결을 기다린다. 스냅샷을 두면 회차당 DB 조회는 TTL마다 한 번이다.
 *
 * 정합성: 배치도는 화면 표시용이고, 실제로 선점할 수 있는지는 선점 API가 좌석을 잠근 뒤 다시 판정한다(만료 HOLD는 expireIfDue
 * 조건부 변경). 그래서 TTL만큼 늦은 스냅샷은 409 SEAT_ALREADY_TAKEN이 늘어나는 것으로만 나타나고 이중 선점은 생기지 않는다.
 * 선점·해제 때 캐시를 지우지 않는다. 좌석 상태를 바꾸는 경로가 여럿이라(잔여석 캐시와 같은 이유) 시간이 지나면 다시 만든다.
 * 404 SEAT_NOT_FOUND 같은 예외는 캐시하지 않아 좌석 준비 직후 바로 보인다.
 *
 * Note: 이 클래스에는 트랜잭션을 두지 않는다. 메서드에 트랜잭션이 있으면 캐시에 있어도 진입만으로 DB 연결을 잡아 효과가 없다.
 * 캐시에 없을 때만 loader인 SelectableSeatQueryService가 읽기 전용 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class SelectableSeatSnapshotService {

    private final SelectableSeatQueryService queryService;
    private final SelectableSeatCache cache;

    public SelectableSeatsResult getSelectableSeats(Long scheduleId) {
        return cache.getOrLoad(scheduleId, () -> queryService.getSelectableSeats(scheduleId));
    }
}
