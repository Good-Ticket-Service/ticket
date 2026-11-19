package com.sparta.ticket.reservation.seat.application.remaining;

import com.sparta.ticket.reservation.seat.application.port.RemainingSeatCache;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 등급별 잔여석 조회(GET /api/seats/schedules/{scheduleId}/remaining, #241). 공연 상세 화면에 "VIP 3석 남음"을 보여 주는 공개 API다.
 *
 * 세는 기준: 판매 대상 좌석 중 AVAILABLE만 센다. 좌석 배치도(SelectableSeatQueryService)는 만료됐지만 정리 전인 HOLD를
 * 예약이 결제 중이 아니면 선택 가능으로 보여 주지만, 잔여석은 booking에 묻지 않고 "남지 않음"으로 센다. 이런 좌석은 만료 스케줄러가
 * 곧 AVAILABLE로 돌려놓고, 차이는 좌석이 적게 보이는 쪽으로만 난다. 잔여석은 안내용이고 정확성은 배치도와 선점이 보장한다.
 *
 * 캐시: 집계 결과를 서버 메모리에 짧게(설정값, 3초) 둔다. 선점·해제 때 지우지 않고 시간이 지나면 다시 센다. 좌석 상태를 바꾸는 경로가
 * 선점·해제·만료·결제 확정·취소·좌석 준비로 여럿이라, 경로마다 지우면 booking 코드까지 건드려야 하기 때문이다.
 *
 * Note: 트랜잭션을 열지 않는다. 캐시에 있으면 DB 연결을 잡지 않고, 없으면 집계 쿼리 하나만 실행한다.
 */
@Service
@RequiredArgsConstructor
public class RemainingSeatService {

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final RemainingSeatCache cache;
    private final Clock clock;

    public RemainingSeatsResult getRemaining(Long scheduleId) {
        RemainingSeatCache.Lookup lookup = cache.getOrLoad(scheduleId, () -> count(scheduleId));
        return RemainingSeatsResult.of(lookup.seats(), lookup.cached());
    }

    /** 좌석이 없는 회차(없는 회차·좌석 준비 전)도 오류가 아니라 빈 목록이다. 화면 안내용이라 계약에 추가 오류가 없다. */
    private RemainingSeats count(Long scheduleId) {
        // 집계 시각은 seat·booking이 함께 쓰는 Clock에서 읽는다. 응답의 asOf로 나간다
        LocalDateTime asOf = LocalDateTime.now(clock);
        return new RemainingSeats(scheduleId, scheduleSeatRepository.countRemainingByGrade(scheduleId), asOf);
    }
}
