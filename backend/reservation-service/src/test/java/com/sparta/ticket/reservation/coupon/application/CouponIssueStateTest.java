package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 발급 판단 키를 남겨 둘 시간 계산을 확인한다(#323). Redis·DB 없이 시각만 본다. */
class CouponIssueStateTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0);

    private final CouponIssueState state = new CouponIssueState(
            mock(CouponIssueGate.class),
            mock(UserCouponRepository.class),
            Clock.fixed(NOW.atZone(KST).toInstant(), KST));

    @Test
    @DisplayName("발급 종료 전이면 종료 시각 + 1일까지 남긴다")
    void endPlusRetention() {
        assertThat(state.ttl(NOW.plusDays(3))).isEqualTo(Duration.ofDays(4));
    }

    @Test
    @DisplayName("이미 끝난 쿠폰을 다시 채우면 1일 남긴다. 0 이하를 걸면 채우자마자 지워진다")
    void endedUsesNow() {
        assertThat(state.ttl(NOW.minusDays(5))).isEqualTo(Duration.ofDays(1));
    }
}
