package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 쿠폰 생성 중 Redis 정보를 만들지 못했을 때를 확인한다(#340).
 * 실제 Redis를 멈추면 같은 컨테이너를 쓰는 다른 테스트가 깨지므로 발급 판단을 가짜로 바꿔 분기만 본다.
 */
class CouponServiceCreateTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0);

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final CouponIssueGate issueGate = mock(CouponIssueGate.class);
    private final CouponIssueState issueState = mock(CouponIssueState.class);

    private final CouponService service = new CouponService(
            couponRepository, issueGate, issueState, Clock.fixed(NOW.atZone(KST).toInstant(), KST));

    @Test
    @DisplayName("Redis 장애로 발급 판단 정보를 못 만들어도 생성은 성공한다. 첫 발급 때 DB에서 채운다")
    void createSucceedsWhenRedisFails() {
        when(couponRepository.save(any(Coupon.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(issueState.ttl(any())).thenReturn(Duration.ofDays(2));
        doThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE))
                .when(issueGate)
                .reset(any(), anyInt(), any());

        CouponResult result =
                service.create(new CreateCouponCommand("쿠폰", 1_000, 10, NOW.minusHours(1), NOW.plusDays(1), 7));

        assertThat(result.remainingQuantity()).isEqualTo(10);
    }
}
