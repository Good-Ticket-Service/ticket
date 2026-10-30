package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.venue.VenueSeatGrade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공연장 좌석 등급의 Spring Data 저장소. */
public interface VenueSeatGradeJpaRepository extends JpaRepository<VenueSeatGrade, Long> {

    @Query("SELECT g.name FROM VenueSeatGrade g WHERE g.venueId = :venueId AND g.deletedAt IS NULL")
    List<String> findNamesByVenueId(@Param("venueId") Long venueId);

    @Query("SELECT g.id FROM VenueSeatGrade g WHERE g.venueId = :venueId AND g.deletedAt IS NULL")
    List<Long> findIdsByVenueId(@Param("venueId") Long venueId);
}
