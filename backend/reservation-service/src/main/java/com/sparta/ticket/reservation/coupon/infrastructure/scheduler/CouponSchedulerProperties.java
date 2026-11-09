package com.sparta.ticket.reservation.coupon.infrastructure.scheduler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 쿠폰 스케줄러 설정. 회원 쿠폰 만료 배치(#183)와 발급 판단 맞추는 작업(#323). 값은 config-repo/reservation-service.yml 한 곳에만 둔다. */
@Validated
@ConfigurationProperties(prefix = "reservation.coupon.scheduler")
public record CouponSchedulerProperties(
        @Valid @NotNull Expiry expiry, @Valid @NotNull Reconcile reconcile) {

    /**
     * @param interval 앞 실행이 끝난 뒤 다음 실행까지 쉬는 시간
     * @param batchSize 한 트랜잭션에서 바꾸는 최대 건수
     * @param timeBudget 한 번 실행이 이 시간을 넘기면 남은 건은 다음 실행에 넘긴다
     */
    public record Expiry(
            @NotNull Duration interval,
            @Positive int batchSize,
            @NotNull Duration timeBudget) {}

    /**
     * @param interval 앞 실행이 끝난 뒤 다음 실행까지 쉬는 시간. Redis가 DB와 어긋난 채 남는 최대 시간이 된다
     */
    public record Reconcile(@NotNull Duration interval) {}
}
