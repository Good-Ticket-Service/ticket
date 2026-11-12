package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.domain.model.settlement.SettlementJobTransport;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 신규 작업의 전달 경로만 선택한다. Kafka 전환은 전체 처리자 배포·검증 뒤에 한다. */
@ConfigurationProperties("payment.settlement.posting")
public record SettlementPostingProperties(
        @DefaultValue("DB") SettlementJobTransport transport) {
    public SettlementPostingProperties {
        if (transport == null) throw new IllegalArgumentException("정산 전달 경로가 필요하다");
    }
}
