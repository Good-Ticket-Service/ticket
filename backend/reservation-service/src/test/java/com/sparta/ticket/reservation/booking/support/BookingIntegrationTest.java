package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * booking 통합 테스트의 공통 부모. 컨텍스트를 함께 쓰므로 테스트마다 예약 행, 시계, 가짜 좌석·결제를 처음 상태로 되돌린다.
 *
 * Note: 이 클래스에는 애너테이션을 붙이지 않는다. @Import나 @TestPropertySource 같은 설정이 하나라도 다르면 Spring 컨텍스트가
 * 따로 떠서 스키마 스크립트가 다시 실행되고, 이미 있는 테이블의 CREATE TABLE이 실패한다. 설정은 ReservationIntegrationTest에만 둔다.
 */
public abstract class BookingIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected FakePaymentPort payment;

    @Autowired
    protected FakeSeatPort seats;

    @Autowired
    protected TableCouponPort coupons;

    @Autowired
    protected MeterRegistry meterRegistry;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** JUnit은 부모의 @BeforeEach를 자식의 것보다 먼저 실행한다. 자식은 이 초기화 위에 자기 준비만 더한다. */
    @BeforeEach
    void resetBookingFixtures() {
        jdbcTemplate.update("DELETE FROM p_tickets");
        jdbcTemplate.update("DELETE FROM p_reservations");
        coupons.reset();
        clock.set(BookingTestConfig.START_TIME);
        payment.reset();
        seats.reset();
    }

    protected String statusOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM p_reservations WHERE reservation_id = ?", String.class, reservationId);
    }

    protected double count(String meter) {
        return meterRegistry.counter(meter).count();
    }

    /** 확정하지 못한 결제 성공, 곧 자동 환불 대상 수. */
    protected double lateSuccessCount() {
        return count("reservation.payment.late_success");
    }
}
