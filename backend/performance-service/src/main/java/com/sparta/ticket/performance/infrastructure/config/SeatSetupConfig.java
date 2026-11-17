package com.sparta.ticket.performance.infrastructure.config;

import com.sparta.ticket.performance.application.dto.SeatSetupRecoveryPolicy;
import com.sparta.ticket.performance.application.dto.SeatSetupRetryPolicy;
import com.sparta.ticket.performance.application.service.SeatSetupDispatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 좌석 준비 설정. 재시도(이슈 #131), 결과 불명 복구(이슈 #252), 커밋 뒤 비동기 실행기(이슈 #466)다.
 * 재시도 기본값은 최대 8회, 간격 15초에서 두 배씩 늘려 상한 5분이다(첫 시도부터 약 18분).
 * 이 길이는 409 SEAT_SETUP_IN_USE를 기다리기 위한 것이다. 선점(HOLD)은 10분이고 만료 뒤 정리될 때까지 남는다(이슈 #175).
 * Note: 첫 간격과 복구 임대는 호출 중에 다른 실행(재시도 스케줄러, 다른 서버)이 같은 회차를 다시 보내지 않게 하는 임대 시간이다.
 * 예상 HTTP 대기(connect 1초 + read 10초)보다 여유를 둔 값일 뿐 처리 전체의 상한은 아니다. 임대는 좌석 목록 조회 전에 시작되고,
 * 요청 작성·결과 반영·DB 지연은 HTTP 대기에 들어 있지 않다. 임대를 넘겨 다시 보내도 같은 requestId·configVersion이라
 * 좌석이 같은 요청으로 처리하고, 바뀐 구성의 옛 결과는 반영하지 않는다(SeatSetupAttempts.finish). 타임아웃을 바꾸면 함께 검토한다.
 */
@Configuration
public class SeatSetupConfig {

    static final String REJECTED_METRIC = "performance.seat.setup.dispatch.rejected";

    @Bean
    public SeatSetupRecoveryPolicy seatSetupRecoveryPolicy(
            @Value("${performance.seat-setup.recovery.max-attempts:3}") int maxAttempts,
            @Value("${performance.seat-setup.recovery.first-delay:30s}") Duration firstDelay,
            @Value("${performance.seat-setup.recovery.max-delay:120s}") Duration maxDelay,
            @Value("${performance.seat-setup.recovery.lease:20s}") Duration lease) {
        return new SeatSetupRecoveryPolicy(maxAttempts, firstDelay, maxDelay, lease);
    }

    @Bean
    public SeatSetupRetryPolicy seatSetupRetryPolicy(
            @Value("${performance.seat-setup.retry.max-attempts:8}") int maxAttempts,
            @Value("${performance.seat-setup.retry.first-delay:15s}") Duration firstDelay,
            @Value("${performance.seat-setup.retry.max-delay:5m}") Duration maxDelay) {
        return new SeatSetupRetryPolicy(maxAttempts, firstDelay, maxDelay);
    }

    /**
     * 커밋 뒤 좌석 준비를 보내는 실행기. 기본 스레드 1개, 큐 200건이다(이슈 #466).
     * 큐가 차면 거절 수를 세고 거절한다. 요청 스레드에서 대신 돌리지 않는다(판매자 응답이 다시 좌석에 묶인다).
     * 거절된 회차는 PENDING으로 남아 재시도 스케줄러가 보낸다. 큐 길이·활성 스레드는 Spring Boot가 executor.* 지표로 노출한다.
     * Note: 종료 때 남은 작업을 기다리게 하지 않는다(기본값). 버려지거나 끊긴 작업은 PENDING과 임대로 다시 보낸다.
     */
    @Bean(name = SeatSetupDispatcher.EXECUTOR)
    public ThreadPoolTaskExecutor seatSetupExecutor(
            @Value("${performance.seat-setup.dispatch.pool-size:1}") int poolSize,
            @Value("${performance.seat-setup.dispatch.queue-capacity:200}") int queueCapacity,
            MeterRegistry meterRegistry) {
        Counter rejected = Counter.builder(REJECTED_METRIC)
                .description("좌석 준비 실행기가 받지 못한 작업 수. 해당 회차는 재시도 스케줄러가 보낸다")
                .register(meterRegistry);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("seat-setup-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            rejected.increment();
            throw new RejectedExecutionException("좌석 준비 실행기 큐가 가득 찼습니다.");
        });
        return executor;
    }
}
