package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.payment.application.service.settlement.SettlementStatementJob;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 KST 04:00 정산서 발행. 시작 시각을 cutoff로 고정해 잡에 넘긴다. 운영 기본은 꺼 두고 환불·보충 검증 뒤 켠다.
 * Note: Spring Batch로 바꾸면 이 클래스가 JobLauncher를 부르고 cutoff를 JobParameter로 넘긴다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.statement.enabled", havingValue = "true")
public class SettlementStatementScheduler {
    private final SettlementStatementJob job;
    private final Clock clock;

    @Scheduled(cron = "${payment.settlement.statement.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void issue() {
        job.run(LocalDateTime.now(clock));
    }
}
