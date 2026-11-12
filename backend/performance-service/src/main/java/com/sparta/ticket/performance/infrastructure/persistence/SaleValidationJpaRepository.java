package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.application.dto.SaleSeat;
import com.sparta.ticket.performance.application.dto.SaleTarget;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 판매 검증의 두 고정 조회(이슈 #405). 회차 단계에서 거절되면 좌석을 읽지 않도록 두 문장을 유지한다.
 * Note: 초안·취소 공연도 읽어 서비스가 거절 사유를 판단한다. 가격·판매 제외가 없어도 좌석은 남도록 LEFT JOIN 조건은 ON에 둔다.
 */
interface SaleValidationJpaRepository extends Repository<Schedule, Long> {

    /** Note: 삭제 조건은 @SQLRestriction과 겹치지만 404 기준을 쿼리에서 바로 읽히게 적는다. */
    @Query("""
            select new com.sparta.ticket.performance.application.dto.SaleTarget(
                s.id, p.id, p.sellerId, p.venueId, p.title, p.genre,
                s.startAt, s.bookingOpenAt, s.bookingCloseAt,
                p.publicationStatus, p.operationStatus, p.version,
                s.operationStatus, s.salesSuspended, s.seatSetupStatus, s.seatConfigVersion)
            from Schedule s
            join Performance p on p.id = s.performanceId
            join Venue v on v.id = p.venueId
            where s.id = :scheduleId
              and s.deletedAt is null and p.deletedAt is null and v.deletedAt is null
            """)
    Optional<SaleTarget> findTarget(@Param("scheduleId") Long scheduleId);

    /**
     * Note: 가격·판매 제외 LEFT JOIN은 부분 UNIQUE(uq_p_performance_grade_prices_grade,
     * uq_p_performance_seat_policies_seat)로 좌석당 한 행을 유지한다.
     */
    @Query("""
            select new com.sparta.ticket.performance.application.dto.SaleSeat(
                seat.id, grade.id, grade.name, policy.id is null, price.price)
            from VenueSeat seat
            join VenueSeatGrade grade on grade.id = seat.venueSeatGradeId
                and grade.venueId = :venueId and grade.deletedAt is null
            left join PerformanceGradePrice price on price.performanceId = :performanceId
                and price.venueSeatGradeId = grade.id and price.deletedAt is null
            left join PerformanceSeatPolicy policy on policy.performanceId = :performanceId
                and policy.venueSeatId = seat.id and policy.deletedAt is null
            where seat.id in :venueSeatIds and seat.venueId = :venueId and seat.deletedAt is null
            """)
    List<SaleSeat> findSeats(
            @Param("performanceId") Long performanceId,
            @Param("venueId") Long venueId,
            @Param("venueSeatIds") Collection<Long> venueSeatIds);
}
