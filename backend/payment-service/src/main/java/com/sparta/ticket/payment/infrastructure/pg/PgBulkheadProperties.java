package com.sparta.ticket.payment.infrastructure.pg;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PG별 동시 호출 상한({@code pg.bulkhead.*}). 한 PG가 느려지거나 멈췄을 때 서킷 브레이커가 열리기 전까지 그 PG 대기가 요청 스레드를
 * 다 쓰지 못하게 한다. 승인·준비·조회·취소를 요청 경로·복구·환불 구분 없이 같은 PG 예산으로 센다.
 *
 * @param enabled 끄면 호출을 그대로 통과시킨다
 * @param tossMaxConcurrent 토스로 동시에 보낼 수 있는 호출 수
 * @param kakaoMaxConcurrent 카카오로 동시에 보낼 수 있는 호출 수
 * @param maxWait 자리를 기다리는 최대 시간. 넘으면 PG로 보내지 않고 실패로 돌려준다(PG가 처리하지 않았음이 확실하다)
 */
@ConfigurationProperties("pg.bulkhead")
public record PgBulkheadProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("100") int tossMaxConcurrent,
        @DefaultValue("100") int kakaoMaxConcurrent,
        @DefaultValue("50ms") Duration maxWait) {

    public PgBulkheadProperties {
        if (tossMaxConcurrent < 1 || kakaoMaxConcurrent < 1) {
            throw new IllegalArgumentException("PG 동시 호출 상한은 1 이상이어야 한다");
        }
        if (maxWait == null || maxWait.isNegative()) {
            throw new IllegalArgumentException("PG 동시 호출 대기 시간은 0 이상이어야 한다");
        }
    }
}
