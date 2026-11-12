package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 공개 회차 단건의 고정 조회. 조건이 고정이라 QueryDSL 대신 JPQL 문자열로 둔다(이슈 #387).
 * Note: 회차와 부모 공연의 공개 여부를 조인 한 번에 읽는다. 공개 공연 조건은 PublicPerformanceCondition과 같다.
 */
interface PublicScheduleJpaRepository extends Repository<Schedule, Long> {

    /** 공개·미삭제 공연(공연장 미삭제)에 속한 삭제되지 않은 회차와 공연 운영 상태. 취소 회차·취소 공연도 돌려준다. */
    @Query("select new com.sparta.ticket.performance.infrastructure.persistence.PublicScheduleWithStatusRow("
            + " s.id, s.performanceId, s.startAt, s.bookingOpenAt, s.bookingCloseAt,"
            + " s.operationStatus, s.seatSetupStatus, s.salesSuspended, s.canceledAt, p.operationStatus)"
            + " from Schedule s join Performance p on p.id = s.performanceId join Venue v on v.id = p.venueId"
            + " where s.id = :id and s.deletedAt is null"
            + " and p.publicationStatus = :published and p.deletedAt is null and v.deletedAt is null")
    Optional<PublicScheduleWithStatusRow> findPublicSchedule(
            @Param("id") Long id, @Param("published") PublicationStatus published);
}
