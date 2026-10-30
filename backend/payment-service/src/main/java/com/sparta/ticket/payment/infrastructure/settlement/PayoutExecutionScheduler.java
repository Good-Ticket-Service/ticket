package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * READY 지급 주기 실행. 운영 기본은 꺼 두고 20-9 recovery·20-11 관측을 검증한 뒤 켠다(settlement-todo 활성화 관문 3).
 * 첫 실행 시각은 정산서 발행 때 다음 영업일 04:10으로 정해 두므로 여기서는 짧은 주기로 도래한 건만 찾는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.payout.enabled", havingValue = "true")
public class PayoutExecutionScheduler {
    private final PayoutExecutionJob job;

    @Scheduled(
            fixedDelayString = "${payment.settlement.payout.fixed-delay-ms:60000}",
            initialDelayString = "${payment.settlement.payout.initial-delay-ms:60000}")
    public void execute() {
        job.runOnce();
    }
}
