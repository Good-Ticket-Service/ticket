package com.sparta.ticket.reservation.coupon.infrastructure.scheduler;

import com.sparta.ticket.reservation.coupon.application.UserCouponExpiryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 회원 쿠폰 만료 배치 스케줄러를 켠다. reservation.coupon.scheduler.enabled=true일 때만 켜진다.
 * Note: booking 스케줄러와 달리 설정이 없으면 꺼진다. 통합 테스트는 이 설정을 넣지 않으므로 공용 테스트 부모를 고치지 않고도 꺼진 채로 돈다.
 * 운영 값은 config-repo에서 켠다. 꺼져 있어도 만료 판단은 만료 시각으로 하므로 쿠폰 사용에는 영향이 없다.
 * Note: @EnableScheduling을 쓰지 않는다. booking과 같은 이유로 쿠폰 전용 스레드에서 돌리고 TaskScheduler 빈으로 내놓지 않는다.
 */
@Configuration
@EnableConfigurationProperties(CouponSchedulerProperties.class)
@ConditionalOnProperty(prefix = "reservation.coupon.scheduler", name = "enabled", havingValue = "true")
public class CouponSchedulingConfig {

    @Bean
    public CouponExpiryScheduleRunner couponExpiryScheduleRunner(
            CouponSchedulerProperties properties, UserCouponExpiryService expiryService) {
        return new CouponExpiryScheduleRunner(properties, expiryService);
    }
}
