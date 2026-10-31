package com.sparta.ticket.reservation.seat.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 좌석 준비가 회차 좌석을 한꺼번에 만들고 바꾸는 저장소 기능. 구현은 infrastructure의 JDBC 저장소가 맡는다.
 * 좌석 수백~수천 개를 한 번에 쓰므로 Entity를 거치지 않는다. 좌석 PK가 IDENTITY라 JPA로는 INSERT를 묶어 보내지 못한다.
 * 모든 메서드는 트랜잭션 안에서 부른다.
 */
public interface ScheduleSeatSetupRepository {

    /** 같은 회차의 좌석 준비를 차례로 처리하도록 트랜잭션이 끝날 때까지 회차 단위 잠금을 잡는다. 선점은 이 잠금을 쓰지 않는다. */
    void lockSchedule(Long scheduleId);

    /** 회차의 좌석을 논리 삭제된 행까지 id 순서로 읽는다. 잠그지 않는다. */
    List<ScheduleSeatSnapshot> findAllIncludingDeleted(Long scheduleId);

    /** 회차의 좌석을 논리 삭제된 행까지 id 오름차순으로 잠그고 읽는다. */
    List<ScheduleSeatSnapshot> findAllForUpdateIncludingDeleted(Long scheduleId);

    /** 새 좌석을 AVAILABLE로 만든다. */
    void insertAll(Long scheduleId, long configVersion, List<ScheduleSeatSpec> seats, LocalDateTime at, String by);

    /** 기존 행(좌석 id → 새 값)을 새 구성 값으로 바꾸고 AVAILABLE로 둔다. 논리 삭제된 행이면 되살린다. */
    void updateAll(long configVersion, Map<Long, ScheduleSeatSpec> seatsById, LocalDateTime at, String by);

    /**
     * 새 구성에서 빠진 좌석을 논리 삭제한다. 구성 버전도 새 버전으로 바꿔, 삭제된 행까지 포함해 회차에 반영된 버전을 알 수 있게 한다.
     * 그래야 모든 좌석이 삭제된 회차에도 낮은 버전 요청을 STALE로 막을 수 있다.
     */
    void softDeleteAll(long configVersion, Collection<Long> ids, LocalDateTime at, String by);

    /** 삭제되지 않은 회차 좌석 수와 그중 판매 대상 수. */
    ScheduleSeatCounts countActive(Long scheduleId);
}
