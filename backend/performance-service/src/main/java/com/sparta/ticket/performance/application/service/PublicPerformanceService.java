package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.PublicPerformanceDetail;
import com.sparta.ticket.performance.application.dto.PublicPerformancePage;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSearchCondition;
import com.sparta.ticket.performance.application.port.PublicPerformanceDetailCache;
import com.sparta.ticket.performance.application.port.PublicPerformanceReader;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일반 사용자용 공연 목록·상세 조회. 명세는 .local/performance/api.md 「공개 조회」다.
 * 상세는 조회 DTO를 Redis에 캐시한다(이슈 #398).
 * Note: 클래스에 @Transactional을 붙이지 않는다. JpaTransactionManager는 트랜잭션을 시작할 때 DB 연결을 바로 잡으므로,
 * 상세의 캐시 확인을 트랜잭션 안에서 하면 적중해도 Hikari 연결을 쓴다. 상세는 DB 조회(로더)만 읽기 전용 트랜잭션으로 감싼다.
 */
@Service
public class PublicPerformanceService {

    private final PublicPerformanceReader reader;
    private final PublicPerformanceDetailCache detailCache;
    private final InFlightLoads<Long, Optional<PublicPerformanceDetail>> detailLoads;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public PublicPerformanceService(
            PublicPerformanceReader reader,
            PublicPerformanceDetailCache detailCache,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters,
            Clock clock) {
        this.reader = reader;
        this.detailCache = detailCache;
        this.detailLoads = new InFlightLoads<>(meters, "performance.detail.load");
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * from이 to보다 늦으면 400 INVALID_TIME_RANGE.
     * Note: 목록은 페이지 ID·표시 정보와 기간·전체 개수를 쿼리 여러 개로 읽는다. 그 사이에 커밋된 변경이 섞여 한 응답 안에서
     * 항목이 빠지거나 개수가 어긋나지 않도록 REPEATABLE READ로 같은 시점을 읽는다(이슈 #343).
     * 상태·정렬·전체 개수의 시간 경계도 일치하도록 현재 시각은 한 번만 읽어 Reader에 전달한다(이슈 #382).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicPerformancePage search(PublicPerformanceSearchCondition condition) {
        if (condition.from() != null
                && condition.to() != null
                && condition.from().isAfter(condition.to())) {
            throw new BusinessException(PerformanceErrorCode.INVALID_TIME_RANGE);
        }
        return reader.search(condition, LocalDateTime.now(clock));
    }

    /**
     * 초안·삭제·미존재 공연은 404 PERFORMANCE_NOT_FOUND. 취소된 공개 공연은 조회한다.
     * 캐시 확인 → 없으면 같은 공연을 채우는 진행 중인 조회에 합류하거나 로더가 된다. 404는 저장하지 않는다.
     */
    public PublicPerformanceDetail getDetail(Long performanceId) {
        return detailCache
                .find(performanceId)
                .or(() -> detailLoads.load(performanceId, () -> loadDetail(performanceId)))
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
    }

    /**
     * Note: 로더가 된 뒤 캐시를 한 번 더 본다. 처음 미적중한 뒤 잠시 멈춘 사이 앞 로더가 저장과 항목 정리를 끝냈으면,
     * 이 요청이 새 로더가 되어 이미 저장된 값을 DB에서 다시 읽기 때문이다.
     */
    private Optional<PublicPerformanceDetail> loadDetail(Long performanceId) {
        Optional<PublicPerformanceDetail> cached = detailCache.find(performanceId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PublicPerformanceDetail> detail = readOnly.execute(status -> reader.findDetail(performanceId));
        detail.ifPresent(detailCache::put);
        return detail;
    }
}
