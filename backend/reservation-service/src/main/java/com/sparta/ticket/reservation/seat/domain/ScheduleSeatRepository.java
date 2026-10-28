package com.sparta.ticket.reservation.seat.domain;

import java.util.Collection;
import java.util.List;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface ScheduleSeatRepository {

    ScheduleSeat save(ScheduleSeat seat);

    /** 저장하고 바로 DB에 쓴다. 제약 위반을 호출 안에서 알아야 할 때 쓴다. */
    ScheduleSeat saveAndFlush(ScheduleSeat seat);

    /** 넘긴 좌석을 id 오름차순으로 잠그고 읽는다. 없는 id와 삭제된 좌석은 결과에서 빠진다. 트랜잭션 안에서만 부른다. */
    List<ScheduleSeat> findAllByIdsForUpdate(Collection<Long> ids);
}
