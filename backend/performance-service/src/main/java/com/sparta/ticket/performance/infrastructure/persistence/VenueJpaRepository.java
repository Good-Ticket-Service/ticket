package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.venue.Venue;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공연장의 Spring Data 저장소. 포트 VenueStore는 VenueStoreAdapter가 이 인터페이스로 구현한다. */
public interface VenueJpaRepository extends JpaRepository<Venue, Long> {

    /** 공연장 행을 FOR UPDATE로 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Venue v WHERE v.id = :id AND v.deletedAt IS NULL")
    Optional<Venue> findByIdForUpdate(@Param("id") Long id);

    /** 공연장 행을 FOR SHARE로 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT v FROM Venue v WHERE v.id = :id AND v.deletedAt IS NULL")
    Optional<Venue> findByIdForShare(@Param("id") Long id);

    /** Note: Performance 엔티티의 @SQLRestriction을 피하려고 네이티브 쿼리로 삭제된 공연까지 센다(결정 3). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM p_performances WHERE venue_id = :venueId)", nativeQuery = true)
    boolean isUsedByPerformance(@Param("venueId") Long venueId);
}
