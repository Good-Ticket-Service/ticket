package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.port.pg.PgApprovalGate;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
