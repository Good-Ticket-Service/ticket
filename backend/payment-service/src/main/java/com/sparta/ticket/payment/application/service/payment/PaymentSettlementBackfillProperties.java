package com.sparta.ticket.payment.application.service.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 정산 보충 잡 설정. 켜고 끄는 것은 payment.settlement.backfill.enabled(스케줄러 조건)로 한다.
 * 보충 잡은 계산 실패(UNPOSTED) SALE을 다시 계산한다. 포착 누락은 정산 반영 작업·워커가 맡는다(#304).
 *
 * @param batchSize 한 번 읽는 후보 수
 * @param maxPerRun 한 번 실행에서 처리할 최대 결제 수. 남은 대상은 다음 실행이 처음부터 다시 훑는다
 */
@ConfigurationProperties("payment.settlement.backfill")
public record PaymentSettlementBackfillProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("1000") int maxPerRun) {

    public PaymentSettlementBackfillProperties {
        if (batchSize < 1 || maxPerRun < batchSize)
            throw new IllegalArgumentException("정산 보충 batch-size는 1 이상, max-per-run은 batch-size 이상이다");
    }
}
