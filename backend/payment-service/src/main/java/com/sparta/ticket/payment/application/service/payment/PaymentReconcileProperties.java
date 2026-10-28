package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.domain.model.payment.ReconcileSchedule;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.reconcile")
public record PaymentReconcileProperties(
        @DefaultValue({"1", "5", "15", "60", "1440"}) List<Long> checkpointsMinutes,
        @DefaultValue("30") long leaseSeconds,
        @DefaultValue("30") long retryInitialSeconds,
        @DefaultValue("300") long retryMaxSeconds,
        @DefaultValue("0.2") double retryJitterRatio,
        @DefaultValue("5") long unknownWarningMinutes,
        @DefaultValue("15") long unknownCriticalMinutes,
        @DefaultValue("50") int batchSize,
        @DefaultValue("15") long maxRunSeconds) {
    public PaymentReconcileProperties {
        checkpointsMinutes = new ReconcileSchedule(checkpointsMinutes).minutes();
        if (leaseSeconds < 1
                || leaseSeconds > 86_400
                || retryInitialSeconds < 1
                || retryInitialSeconds > retryMaxSeconds
                || retryMaxSeconds > 86_400
                || !Double.isFinite(retryJitterRatio)
                || retryJitterRatio < 0
                || retryJitterRatio >= 1
                || unknownWarningMinutes < 1
                || unknownCriticalMinutes <= unknownWarningMinutes
                || unknownCriticalMinutes > 525_600
                || batchSize < 1
                || maxRunSeconds < 1) {
            throw new IllegalArgumentException("Reconcile 재시도·점유·경고·실행 설정 범위가 올바르지 않다");
        }
    }

    public ReconcileSchedule schedule() {
        return new ReconcileSchedule(checkpointsMinutes);
    }
}
