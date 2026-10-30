package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.port.pg.PgApprovalGate;
import com.sparta.ticket.payment.application.service.payment.PaymentReconcileProperties;
import com.sparta.ticket.payment.application.service.payment.PaymentRecoveryProperties;
import com.sparta.ticket.payment.application.service.payment.PaymentSettlementBackfillProperties;
import com.sparta.ticket.payment.application.service.refund.RefundRecoveryProperties;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
    PaymentRecoveryProperties.class,
    PaymentReconcileProperties.class,
    RefundRecoveryProperties.class,
    PaymentSettlementBackfillProperties.class
})
public class PaymentExecutionConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock paymentClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    @ConditionalOnMissingBean(PgApprovalGate.class)
    PgApprovalGate pgApprovalGate() {
        return provider -> true;
    }
}
