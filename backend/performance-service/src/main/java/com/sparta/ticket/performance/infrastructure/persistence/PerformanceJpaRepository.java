package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.Performance;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공연의 Spring Data 저장소. 포트 PerformanceStore는 PerformanceStoreAdapter가 이 인터페이스로 구현한다. */
public interface PerformanceJpaRepository extends JpaRepository<Performance, Long> {

    /** 공연 행을 FOR UPDATE로 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Performance p WHERE p.id = :id AND p.deletedAt IS NULL")
    Optional<Performance> findByIdForUpdate(@Param("id") Long id);

    /** 공연의 운영 상태만 잠그지 않고 읽는다. 삭제된 공연은 empty. */
    @Query("SELECT p.operationStatus FROM Performance p WHERE p.id = :id AND p.deletedAt IS NULL")
    Optional<OperationStatus> findOperationStatus(@Param("id") Long id);
}
