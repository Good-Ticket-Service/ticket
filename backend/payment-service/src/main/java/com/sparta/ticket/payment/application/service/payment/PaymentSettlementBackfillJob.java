package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.service.observation.JobObservation;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 포착이 빠진 결제와 계산에 실패한 SALE을 결제 ID 순으로 훑어 메운다(Task 20-4).
 * 한 결제가 실패해도 커서를 넘겨 뒤 결제를 계속 처리한다. 실패한 결제는 다음 실행이 처음부터 훑을 때 다시 본다.
 */
@Slf4j
@Component
public class PaymentSettlementBackfillJob {

    private final PaymentSettlementBackfillTransactions transactions;
    private final PaymentSettlementBackfillProperties properties;
    private final JobObservation observation;

    public PaymentSettlementBackfillJob(
            PaymentSettlementBackfillTransactions transactions,
            PaymentSettlementBackfillProperties properties,
            MeterRegistry meters) {
        this.transactions = transactions;
        this.properties = properties;
        this.observation = new JobObservation(meters, "settlement-backfill");
    }

    public void runOnce() {
        observation.run(this::executeOnce);
    }

    private void executeOnce() {
        long cursor = 0;
        int processed = 0;
        while (processed < properties.maxPerRun()) {
            List<Long> ids = transactions.candidates(
                    cursor, Math.min(properties.batchSize(), properties.maxPerRun() - processed));
            if (ids.isEmpty()) return;
            for (Long paymentId : ids) {
                cursor = paymentId;
                processed++;
                observation.claimed();
                try {
                    transactions.backfill(paymentId);
                } catch (RuntimeException e) {
                    observation.failed();
                    log.error("정산 보충 실패. 다음 결제로 넘어가고 다음 실행에서 다시 본다. paymentId={}", paymentId, e);
                }
            }
        }
    }
}
