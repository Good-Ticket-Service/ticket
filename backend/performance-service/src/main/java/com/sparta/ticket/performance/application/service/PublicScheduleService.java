package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.dto.PublicSchedule;
import com.sparta.ticket.performance.application.dto.PublicScheduleDetailRow;
import com.sparta.ticket.performance.application.dto.PublicScheduleList;
import com.sparta.ticket.performance.application.port.PublicScheduleListCache;
import com.sparta.ticket.performance.application.port.PublicScheduleReader;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일반 사용자용 회차 목록·상세 조회. 판매 상태는 서버 시각으로 계산한다. 명세는 .local/performance/api.md 「공개 조회」다.
 * 목록은 부모 공연이 공개 공연인지 먼저 확인하고 회차를 읽는다. 단건은 회차와 공개 공연 확인을 한 번에 읽는다.
 */
@Service
public class PublicScheduleService {

    private final PublicScheduleReader reader;
    private final Clock clock;
    private final PublicScheduleListCache cache;
    private final InFlightLoads<Long, Optional<PublicScheduleList>> loads;
    private final TransactionTemplate readOnly;

    public PublicScheduleService(
            PublicScheduleReader reader,
            PublicScheduleListCache cache,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters,
            Clock clock) {
        this.reader = reader;
        this.cache = cache;
        this.clock = clock;
        this.loads = new InFlightLoads<>(meters, "performance.schedule.list.load");
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** 초안·삭제·미존재 공연은 404 PERFORMANCE_NOT_FOUND. 회차가 없으면 빈 목록이다. */
    public List<PublicSchedule> getSchedules(Long performanceId) {
        PublicScheduleList data = cache.find(performanceId)
                .or(() -> loads.load(performanceId, () -> loadSchedules(performanceId)))
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
        // Note: 공유 조회 대기 중 오픈·마감 경계를 넘을 수 있어 각 요청이 값을 얻은 뒤 시각을 구한다.
        LocalDateTime now = LocalDateTime.now(clock);
        return data.schedules().stream()
                .map(row -> PublicSchedule.of(row, data.performanceStatus(), now))
                .toList();
    }

    /** 캐시 확인·저장은 트랜잭션 밖에서 하고 기존 SQL 두 개만 같은 읽기 전용 트랜잭션에서 실행한다. */
    private Optional<PublicScheduleList> loadSchedules(Long performanceId) {
        Optional<PublicScheduleList> cached = cache.find(performanceId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PublicScheduleList> data = readOnly.execute(status -> reader.findPublicPerformanceStatus(performanceId)
                .map(performanceStatus ->
                        new PublicScheduleList(performanceStatus, reader.findSchedules(performanceId))));
        data.ifPresent(value -> cache.put(performanceId, value));
        return data;
    }

    /** 회차가 없거나 삭제됐거나, 부모 공연이 초안·삭제됐으면 404 SCHEDULE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public PublicSchedule getSchedule(Long scheduleId) {
        PublicScheduleDetailRow row = reader.findPublicSchedule(scheduleId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.SCHEDULE_NOT_FOUND));
        return PublicSchedule.of(row.schedule(), row.performanceStatus(), LocalDateTime.now(clock));
    }
}
