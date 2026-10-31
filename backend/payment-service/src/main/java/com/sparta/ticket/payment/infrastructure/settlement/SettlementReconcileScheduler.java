package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.payment.application.service.settlement.SettlementReconciliationJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 KST 03:00 정산 전체 대조(04:00 발행 전). 실패는 실행 기록에 남고, 마지막 성공 시각이 오래되면 경보가 뜬다. 운영 기본은 꺼 두고
 * 정산 발행을 켤 때 함께 켠다. 여러 인스턴스가 같이 돌아도 읽기만 하므로 금융 상태를 바꾸지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.reconcile.enabled", havingValue = "true")
public class SettlementReconcileScheduler {
    private final SettlementReconciliationJob job;

    @Scheduled(cron = "${payment.settlement.reconcile.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void reconcile() {
        try {
            job.run();
        } catch (RuntimeException e) {
            // Note: 실패는 실행 기록과 로그에 이미 남았다. 스케줄러가 다음 실행을 계속하게 삼킨다.
            log.debug("정산 대조 실패를 기록했다", e);
        }
    }
}
