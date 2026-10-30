package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.infrastructure.coupon.CouponUsageAdapter;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 통합 테스트용 대역. 시각은 테스트가 정하고, 좌석과 결제는 기록하는 가짜로, 쿠폰은 실제 구현을 쓰되 동시 진입과 잠금 실패를 끼워 넣을 수 있게 감싼다. */
@TestConfiguration
public class BookingTestConfig {

    /** 테스트 시계가 시작하는 시각. 통합 테스트는 매번 이 시각으로 되돌리고 시작한다. */
    public static final LocalDateTime START_TIME = LocalDateTime.of(2026, 11, 1, 20, 0);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(START_TIME);
    }

    @Bean
    @Primary
    public FakeSeatPort fakeSeatPort() {
        return new FakeSeatPort();
    }

    @Bean
    @Primary
    public ControlledCouponPort controlledCouponPort(CouponUsageAdapter delegate) {
        return new ControlledCouponPort(delegate);
    }

    @Bean
    public CouponFixture couponFixture(JdbcTemplate jdbcTemplate, MutableClock clock) {
        return new CouponFixture(jdbcTemplate, clock);
    }

    @Bean
    @Primary
    public FakePaymentPort fakePaymentPort(DataSource dataSource) throws SQLException {
        FakePaymentPort payment = new FakePaymentPort();
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        payment.watchConnections(() -> hikari.getHikariPoolMXBean().getActiveConnections());
        return payment;
    }
}
