package com.sparta.ticket.reservation.seat.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface ScheduleSeatRepository {

    ScheduleSeat save(ScheduleSeat seat);

    /** 저장하고 바로 DB에 쓴다. 제약 위반을 호출 안에서 알아야 할 때 쓴다. */
    ScheduleSeat saveAndFlush(ScheduleSeat seat);

    /** 좌석 하나를 잠그지 않고 읽는다. 삭제된 좌석은 빈 값이다. */
    Optional<ScheduleSeat> findById(Long id);

    /** 넘긴 좌석을 id 오름차순으로 잠그고 읽는다. 없는 id와 삭제된 좌석은 결과에서 빠진다. 트랜잭션 안에서만 부른다. */
    List<ScheduleSeat> findAllByIdsForUpdate(Collection<Long> ids);

    /**
     * {@link #findAllByIdsForUpdate}와 같지만 잠금을 기다리지 않는다(FOR UPDATE NOWAIT). 넘긴 좌석 중 하나라도 다른 트랜잭션이
     * 잠그고 있으면 바로 PessimisticLockingFailureException(SQLSTATE 55P03)으로 끝난다. 선점 경로에서만 쓴다(#313).
     */
    List<ScheduleSeat> findAllByIdsForUpdateNoWait(Collection<Long> ids);

    /** 회차의 좌석을 id 순서로 읽는다. 잠그지 않는다. 삭제된 좌석은 빠진다. */
    List<ScheduleSeat> findAllBySchedule(Long scheduleId);

    /**
     * 회차의 등급별 남은 좌석 수를 등급 id 오름차순으로 센다. 판매 대상 좌석 중 AVAILABLE만 세고, 판매 대상 좌석이 없는 등급은 빠진다.
     * 잠그지 않는다. 삭제된 좌석은 빠진다.
     */
    List<GradeRemaining> countRemainingByGrade(Long scheduleId);

    /** 이 예약의 HOLD 좌석만 SOLD로 바꾸고 만료 시각을 비운다. 바뀐 행 수를 돌려준다. */
    int sell(Collection<Long> ids, Long reservationId, LocalDateTime at, String by);

    /** 이 예약이 가진 좌석만 AVAILABLE로 돌리고 예약과 만료 시각을 비운다. 바뀐 행 수를 돌려준다. */
    int release(Collection<Long> ids, Long reservationId, LocalDateTime at, String by);
}
