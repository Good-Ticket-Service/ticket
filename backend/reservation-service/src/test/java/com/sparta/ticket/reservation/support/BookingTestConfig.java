package com.sparta.ticket.reservation.support;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 통합 테스트용 대역. 시각은 테스트가 정하고, 좌석과 결제는 기록하는 가짜로 바꾼다. */
@TestConfiguration
public class BookingTestConfig {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(LocalDateTime.of(2026, 11, 1, 20, 0));
    }

    @Bean
    @Primary
    public FakeSeatPort fakeSeatPort() {
        return new FakeSeatPort();
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
