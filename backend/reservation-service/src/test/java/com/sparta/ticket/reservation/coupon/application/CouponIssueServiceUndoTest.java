package com.sparta.ticket.reservation.coupon.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.IssueDecision;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * 회원 쿠폰 저장이 실패했을 때 Redis 기록을 어떻게 정리하는지 확인한다(#300, "모르면 지우지 않는다").
 * 실제 DB에서 커밋 결과를 모르는 실패를 만들기 어려워 저장소와 발급 판단을 가짜로 바꿔 분기만 본다.
 */
class CouponIssueServiceUndoTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final Long COUPON_ID = 5L;
    private static final Long USER_ID = 200L;

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
    private final CouponIssueGate issueGate = mock(CouponIssueGate.class);
    private final CouponIssueState issueState = mock(CouponIssueState.class);

    private CouponIssueService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        service = new CouponIssueService(couponRepository, userCouponRepository, issueGate, issueState, clock);
        // 발급 중인 쿠폰이고, Redis 판단은 통과한다
        Coupon coupon = Coupon.create("쿠폰", 5_000, 10, NOW.minusHours(1), NOW.plusDays(1), 7, NOW.minusDays(1));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(issueGate.tryIssue(COUPON_ID, USER_ID, true)).thenReturn(IssueDecision.ISSUED);
    }

    private void issueFails(RuntimeException failure) {
        when(userCouponRepository.saveIssued(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.issue(COUPON_ID, USER_ID, "user")).isSameAs(failure);
    }

    @Test
    @DisplayName("저장이 실패했고 DB에 없으면 Redis 기록을 지운다")
    void cancelWhenNotSaved() {
        when(userCouponRepository.existsByUserIdAndCouponId(USER_ID, COUPON_ID)).thenReturn(false);

        issueFails(new QueryTimeoutException("시간 초과"));

        verify(issueGate).cancel(COUPON_ID, USER_ID);
    }

    @Test
    @DisplayName("저장이 실패했지만 DB에 있으면(커밋 중 연결이 끊겨 결과만 못 받은 경우) 남긴다")
    void keepWhenActuallySaved() {
        when(userCouponRepository.existsByUserIdAndCouponId(USER_ID, COUPON_ID)).thenReturn(true);

        issueFails(new QueryTimeoutException("커밋 결과 모름"));

        verify(issueGate, never()).cancel(COUPON_ID, USER_ID);
    }

    @Test
    @DisplayName("DB 확인도 실패하면 남긴다. 잘못 지우면 초과 발급이 생긴다")
    void keepWhenCannotCheck() {
        when(userCouponRepository.existsByUserIdAndCouponId(USER_ID, COUPON_ID))
                .thenThrow(new QueryTimeoutException("DB 안 됨"));

        issueFails(new QueryTimeoutException("시간 초과"));

        verify(issueGate, never()).cancel(COUPON_ID, USER_ID);
    }

    @Test
    @DisplayName("같은 회원 중복(UNIQUE 위반)이면 DB에 이미 있어 확인 없이 남긴다")
    void keepWhenAlreadyIssued() {
        issueFails(new BusinessException(CouponErrorCode.COUPON_ALREADY_ISSUED));

        verify(userCouponRepository, never()).existsByUserIdAndCouponId(USER_ID, COUPON_ID);
        verify(issueGate, never()).cancel(COUPON_ID, USER_ID);
    }

    @Test
    @DisplayName("Redis 정보가 없으면 DB에서 채운 뒤 한 번 더 판단한다")
    void reloadWhenNotLoaded() {
        when(issueGate.tryIssue(COUPON_ID, USER_ID, true))
                .thenReturn(IssueDecision.NOT_LOADED)
                .thenReturn(IssueDecision.SOLD_OUT);

        assertThatThrownBy(() -> service.issue(COUPON_ID, USER_ID, "user"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_SOLD_OUT);
        verify(issueState).ensureLoaded(COUPON_ID, 10, NOW.plusDays(1));
    }
}
