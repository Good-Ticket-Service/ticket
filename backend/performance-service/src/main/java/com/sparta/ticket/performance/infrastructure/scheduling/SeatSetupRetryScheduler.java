package com.sparta.ticket.performance.infrastructure.scheduling;

import com.sparta.ticket.performance.application.service.SeatSetupProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 좌석 준비가 PENDING으로 남은 회차를 같은 requestId로 다시 보낸다(일시 실패, 커밋 뒤 첫 호출 전에 서버가 멈춘 경우).
 * Note: 서버가 여러 대여도 한 회차를 동시에 보내지 않는다. 시도를 시작할 때 회차 행을 잠그고 다음 시도 시각을 미리 옮기기 때문이다
 * (SeatSetupAttempts.start). performance.seat-setup.retry.enabled=false면 뜨지 않는다(통합 테스트는 직접 부른다).
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "performance.seat-setup.retry.enabled", havingValue = "true", matchIfMissing = true)
public class SeatSetupRetryScheduler {

    private final SeatSetupProcessor processor;

    @Value("${performance.seat-setup.retry.batch-size:50}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${performance.seat-setup.retry.fixed-delay-ms:5000}",
            initialDelayString = "${performance.seat-setup.retry.fixed-delay-ms:5000}")
    public void retryDue() {
        processor.processDue(batchSize);
    }
}
