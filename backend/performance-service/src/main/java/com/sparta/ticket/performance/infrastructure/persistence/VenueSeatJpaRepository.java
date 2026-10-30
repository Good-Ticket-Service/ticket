package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.venue.SeatPosition;
import com.sparta.ticket.performance.domain.venue.VenueSeat;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공연장 물리 좌석의 Spring Data 저장소. */
public interface VenueSeatJpaRepository extends JpaRepository<VenueSeat, Long> {

    /** 공연장에서 주어진 구역들에 있는 좌석 위치만 읽는다. 공연장 전체 좌석을 읽지 않으려고 구역으로 좁힌다. */
    @Query("""
            SELECT new com.sparta.ticket.performance.domain.venue.SeatPosition(s.section, s.rowLabel, s.seatNumber)
            FROM VenueSeat s
            WHERE s.venueId = :venueId AND s.section IN :sections AND s.deletedAt IS NULL
            """)
    List<SeatPosition> findPositions(@Param("venueId") Long venueId, @Param("sections") Collection<String> sections);

    /** 공연장 좌석 중 이 공연의 판매 제외 행이 없는 좌석이 쓰는 등급 ID. */
    @Query("""
            SELECT DISTINCT s.venueSeatGradeId
            FROM VenueSeat s
            WHERE s.venueId = :venueId AND s.deletedAt IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM PerformanceSeatPolicy p
                  WHERE p.performanceId = :performanceId AND p.venueSeatId = s.id AND p.deletedAt IS NULL)
            """)
    List<Long> findSellableGradeIds(@Param("performanceId") Long performanceId, @Param("venueId") Long venueId);
}
