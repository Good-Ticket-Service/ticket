package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.application.port.PerformanceStore;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.domain.performance.PerformanceGradePrice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PerformanceStore의 구현. 저장·조회는 Spring Data에 맡기고, version 증가는 Spring Data로 표현할 수 없어 EntityManager로 한다.
 * Note: 가격 행은 부분 UNIQUE가 있지만, 공연 행 잠금 아래에서 등급별로 맞춰 바꾸므로 위반이 생기지 않아 409 변환을 두지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class PerformanceStoreAdapter implements PerformanceStore {

    private final PerformanceJpaRepository performanceRepository;
    private final PerformanceGradePriceJpaRepository gradePriceRepository;
    private final VenueSeatJpaRepository venueSeatRepository;
    private final EntityManager entityManager;

    @Override
    public Performance save(Performance performance) {
        return performanceRepository.save(performance);
    }

    @Override
    public Performance saveAndFlush(Performance performance) {
        return performanceRepository.saveAndFlush(performance);
    }

    @Override
    public Optional<Performance> findByIdForUpdate(Long performanceId) {
        return performanceRepository.findByIdForUpdate(performanceId);
    }

    /**
     * Note: 이미 PESSIMISTIC_WRITE로 읽은 공연을 PESSIMISTIC_FORCE_INCREMENT로 다시 잠그면 version UPDATE가 바로 나가고
     * Entity의 version도 오른다. 공연장 version 증가(VenueStoreAdapter)와 같은 방식이다.
     */
    @Override
    public long increaseVersion(Long performanceId) {
        Performance performance = entityManager.find(Performance.class, performanceId);
        entityManager.lock(performance, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return performance.getVersion();
    }

    @Override
    public List<PerformanceGradePrice> findGradePrices(Long performanceId) {
        return gradePriceRepository.findByPerformanceId(performanceId);
    }

    @Override
    public void saveGradePrices(List<PerformanceGradePrice> prices) {
        gradePriceRepository.saveAll(prices);
    }

    @Override
    public Set<Long> findSellableGradeIds(Long performanceId, Long venueId) {
        return new HashSet<>(venueSeatRepository.findSellableGradeIds(performanceId, venueId));
    }
}
