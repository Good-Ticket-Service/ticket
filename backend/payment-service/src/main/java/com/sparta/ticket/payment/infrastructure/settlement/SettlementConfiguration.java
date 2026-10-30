package com.sparta.ticket.payment.infrastructure.settlement;

import com.sparta.ticket.payment.domain.model.settlement.BusinessCalendarBook;
import com.sparta.ticket.payment.domain.model.settlement.SettlementPolicyBook;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementConfiguration {

    /**
     * 정책·달력 설정이 비었거나 틀리면 여기서 예외가 나 기동이 멈춘다.
     * Note: 정산 포착을 끄는 긴급 설정과 관계없이 항상 검증한다. 잘못된 정책으로 다시 켜는 일을 막기 위해서다.
     */
    @Bean
    SettlementRules settlementRules(SettlementProperties properties) {
        return new SettlementRules(
                new SettlementPolicyBook(map(properties.policies(), SettlementProperties.Policy::toDomain)),
                new BusinessCalendarBook(map(properties.calendars(), SettlementProperties.Calendar::toDomain)));
    }

    private static <T, R> List<R> map(List<T> values, java.util.function.Function<T, R> mapper) {
        return values == null ? List.of() : values.stream().map(mapper).toList();
    }
}
