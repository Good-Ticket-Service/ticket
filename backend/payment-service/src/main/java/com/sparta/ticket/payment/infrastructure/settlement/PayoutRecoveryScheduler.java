package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * REQUESTED 지급 주기 조회(Task 20-9). 지급 스위치(payout.enabled)와 따로 켠다. 지급 요청을 멈춰도 이미 보낸 지급의 결과는 계속
 * 확인해야 하므로, 정산 발행·지급을 운영하는 동안은 켜 둔다. 조회 간격은 지급마다 next_attempt_at으로 정하고, 여기서는 도래한 건만 찾는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.payout.recovery.enabled", havingValue = "true")
public class PayoutRecoveryScheduler {
    private final PayoutExecutionJob job;

    @Scheduled(
            fixedDelayString = "${payment.settlement.payout.recovery.fixed-delay-ms:10000}",
            initialDelayString = "${payment.settlement.payout.recovery.initial-delay-ms:30000}")
    public void recover() {
        job.recoverOnce();
    }
}
