package com.sparta.ticket.reservation.seat.application.setup;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.SeatChangeStamps;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatCounts;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSetupRepository;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSnapshot;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSpec;
import com.sparta.ticket.reservation.seat.domain.SeatStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 좌석 준비(POST /internal/schedules/{scheduleId}/seat-setups, #175). 공연 서비스가 회차를 등록하거나 좌석 구성(가격·판매 제외)을
 * 바꾸면 회차 좌석 전체를 보내고, 좌석은 이 회차의 좌석을 만들거나 새 구성으로 바꾼다.
 *
 * 멱등성과 버전: requestId는 저장하지 않고 (scheduleId, configVersion)으로 같은 요청인지 판단한다. 공연은 configVersion이 바뀔 때만
 * 새 requestId를 쓰므로 두 기준의 결과가 같다(#54).
 *   - 좌석 없음          → 모두 만든다
 *   - 같은 버전이 반영됨  → 바꾸지 않고 저장된 좌석으로 결과를 준다
 *   - 요청 버전이 낮음    → 409 SEAT_SETUP_STALE_VERSION
 *   - 요청 버전이 높음    → 좌석이 모두 AVAILABLE이면 새 구성으로 바꾸고, 아니면 409 SEAT_SETUP_IN_USE
 *
 * 처리는 트랜잭션 하나라 중간에 실패하면 아무것도 남지 않는다.
 *
 * 동시성: 같은 회차의 준비 요청은 회차 단위 advisory 잠금으로 차례로 처리한다. 처음 준비할 때는 잠글 좌석 행이 아직 없어서
 * 행 잠금만으로는 두 요청이 함께 INSERT하는 것을 막지 못하기 때문이다. 타임아웃 뒤 재시도가 첫 요청의 저장 중에 와도 늦은 요청은
 * 잠금을 기다렸다가 같은 버전이 반영된 것을 보고 같은 결과(200)를 준다.
 * 잠금 순서는 회차 advisory 잠금 → 좌석(id 오름차순)이다. 선점은 advisory 잠금을 잡지 않고 좌석 → 예약 순서라 교착하지 않는다.
 * 교체 중 좌석 잠금을 기다리던 선점은 바뀐 구성 버전을 보고 SEAT_CONFIG_VERSION_MISMATCH로, 빠진 좌석이면 SEAT_NOT_FOUND로 끝난다.
 * 잠금은 오래 기다리지 않는다. 대기 상한(ScheduleSeatSetupJdbcRepository.LOCK_TIMEOUT)을 넘기면 오류로 끝내고, 컨트롤러가 503으로
 * 바꿔 공연 쪽이 같은 requestId로 다시 보내게 한다.
 *
 * Note: 격리 수준은 기본값 READ COMMITTED를 전제한다. 문장마다 새 스냅샷으로 읽어야, advisory 잠금을 기다린 늦은 요청이 앞 요청이
 * 커밋한 좌석을 보고 같은 버전으로 판정한다. REPEATABLE READ로 바꾸면 늦은 요청이 트랜잭션 시작 때의 빈 회차를 보고 다시 INSERT해
 * UK 위반이 난다.
 */
@Service
@RequiredArgsConstructor
public class SeatSetupService {

    private final ScheduleSeatSetupRepository repository;
    private final SeatChangeStamps stamps;

    @Transactional
    public SeatSetupResult setup(SeatSetupCommand command) {
        Long scheduleId = command.scheduleId();
        repository.lockSchedule(scheduleId); // 회차 단위 잠금
        // 버전 판정은 잠그지 않고 읽은 값으로 한다. config_version은 좌석 준비만 바꾸고 좌석 준비는 위 잠금으로 차례로 처리되므로
        // 잠그지 않아도 정확하다. 같은 버전 재요청·낮은 버전처럼 아무것도 바꾸지 않는 요청이 회차 좌석을 잠가 선점을 막지 않게 한다
        List<ScheduleSeatSnapshot> current =
                repository.findAllIncludingDeleted(scheduleId); // 회차의 좌석을 논리 삭제된 행까지 읽음(잠그지 않음)

        if (!current.isEmpty()) {
            // 회차 좌석은 늘 한 구성으로 함께 바뀌고, 논리 삭제할 때도 새 버전을 적으므로 삭제된 행까지 모두 같은 버전이다.
            // 삭제된 행까지 보는 이유: 모든 좌석이 삭제된 회차에도 낮은 버전 요청을 STALE로 막기 위해서다
            long appliedVersion = current.stream()
                    .mapToLong(ScheduleSeatSnapshot::configVersion)
                    .max()
                    .orElseThrow();
            if (command.configVersion() == appliedVersion) { // 요청 버전이 같은 경우
                return result(command); // 같은 버전이 반영됨 → 바꾸지 않고 저장된 좌석으로 결과를 준다
            }
            if (command.configVersion() < appliedVersion) { // 요청 버전이 낮은 경우
                throw new BusinessException(ReservationErrorCode.SEAT_SETUP_STALE_VERSION);
            }
        }

        // 바꿀 때만 회차 좌석을 id 오름차순으로 잠그고 다시 읽는다. 좌석 상태(HOLD·SOLD)는 선점·결제가 바꾸므로 잠근 뒤의 값으로 판단한다
        List<ScheduleSeatSnapshot> locked = repository.findAllForUpdateIncludingDeleted(scheduleId);
        // 만료됐지만 아직 정리되지 않은 HOLD도 상태가 HOLD라 여기서 걸린다
        // (architecture 10절: 높은 버전 교체는 기존 모든 좌석이 AVAILABLE일 때만)
        // 논리 삭제된 행도 본다. apply가 되살리는 행의 점유 값을 지우므로, 되살릴 행도 비어 있어야 판 좌석을 지우지 않는다
        if (locked.stream().anyMatch(seat -> seat.status() != SeatStatus.AVAILABLE)) {
            throw new BusinessException(ReservationErrorCode.SEAT_SETUP_IN_USE);
        }

        apply(command, locked);
        return result(command);
    }

    /**
     * 새 구성을 공연장 좌석 ID 기준으로 반영한다. 처음 준비와 높은 버전 교체가 같은 방법을 쓴다(처음 준비는 기존 행이 없어 모두 추가다).
     *
     * 요청 좌석과 기존 행(논리 삭제 포함)을 공연장 좌석 ID로 짝지어 세 묶음으로 나눈다.
     *   1. 기존 행이 있는 좌석 → 그 행의 위치·등급·가격·판매 여부·구성 버전을 새 값으로 바꾼다. 삭제된 행이면 되살린다(updateAll)
     *   2. 기존 행이 없는 좌석 → 새 행을 AVAILABLE로 만든다(insertAll)
     *   3. 기존에 살아 있었는데 새 구성에 없는 좌석 → 논리 삭제한다(softDeleteAll). 구성 버전도 새 버전으로 적는다
     * 세 묶음을 같은 트랜잭션에서 쓰므로 중간에 실패하면 모두 되돌아간다.
     *
     * 예) 기존 v1 = 좌석 {1, 2, 3}, 요청 v2 = 좌석 {2, 3, 4}
     *     → 2·3은 같은 행을 v2 값으로 갱신, 4는 새로 추가, 1은 논리 삭제. 이어서 v3 = {1, 2}가 오면 1은 삭제된 행을 되살린다
     *
     * Note: 지우고 다시 넣지 않는 이유
     *   - UK(schedule_id, venue_seat_id)가 부분 UNIQUE가 아니라, 논리 삭제한 뒤 같은 공연장 좌석을 다시 넣으면 위반이다.
     *   - 물리 삭제하면 해제·만료된 예약의 티켓(p_tickets)이 참조하던 좌석 id가 사라진다. 같은 행을 쓰면 좌석 id가 유지된다.
     * Note: 이 메서드는 회차 좌석을 모두 잠그고 모두 AVAILABLE임을 확인한 뒤에만 부른다. 그래서 갱신·복원 때 점유 값을 비워도 안전하다.
     *
     * @param locked 잠근 뒤 읽은 회차 좌석 전체(논리 삭제 포함)
     */
    private void apply(SeatSetupCommand command, List<ScheduleSeatSnapshot> locked) {
        // 기존 행을 공연장 좌석 ID로 찾는다. UK(schedule_id, venue_seat_id) 때문에 공연장 좌석 하나에 행은 하나뿐이다
        Map<Long, ScheduleSeatSnapshot> byVenueSeat =
                locked.stream().collect(Collectors.toMap(ScheduleSeatSnapshot::venueSeatId, Function.identity()));
        Map<Long, ScheduleSeatSpec> toUpdate = new LinkedHashMap<>(); // 1. 갱신·복원할 행: 좌석 id → 새 값
        List<ScheduleSeatSpec> toInsert = new ArrayList<>(); // 2. 새로 만들 좌석
        for (ScheduleSeatSpec seat : command.seats()) {
            ScheduleSeatSnapshot existing = byVenueSeat.get(seat.venueSeatId());
            if (existing != null) {
                toUpdate.put(existing.id(), seat);
            } else {
                toInsert.add(seat);
            }
        }
        // 3. 지울 행: 지금 살아 있는 행 중 새 구성에 없는 것. 이미 삭제된 행은 그대로 둔다
        Set<Long> requested =
                command.seats().stream().map(ScheduleSeatSpec::venueSeatId).collect(Collectors.toSet());
        List<Long> toDelete = locked.stream()
                .filter(seat -> !seat.deleted() && !requested.contains(seat.venueSeatId()))
                .map(ScheduleSeatSnapshot::id)
                .toList();

        // 감사 값은 세 묶음에 같은 값을 쓴다. 내부 호출이라 회원 헤더가 없으면 SYSTEM이다
        LocalDateTime at = stamps.now();
        String by = stamps.by();
        repository.updateAll(command.configVersion(), toUpdate, at, by);
        repository.insertAll(command.scheduleId(), command.configVersion(), toInsert, at, by);
        repository.softDeleteAll(command.configVersion(), toDelete, at, by);
    }

    /** 좌석 수는 저장된 행에서 다시 센다. 같은 버전 재요청도 처음 결과와 같은 값을 준다. */
    private SeatSetupResult result(SeatSetupCommand command) {
        ScheduleSeatCounts counts = repository.countActive(command.scheduleId());
        return new SeatSetupResult(
                command.requestId(),
                command.scheduleId(),
                command.configVersion(),
                counts.seatCount(),
                counts.sellableCount());
    }
}
