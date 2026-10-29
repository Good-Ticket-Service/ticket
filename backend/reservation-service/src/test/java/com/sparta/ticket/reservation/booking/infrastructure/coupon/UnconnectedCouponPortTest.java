package com.sparta.ticket.reservation.booking.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** coupon 모듈과 연결하지 않은 운영 빈. 쿠폰을 고른 결제 준비를 조용히 정가로 진행하지 않는지 본다. */
class UnconnectedCouponPortTest {

    private final UnconnectedCouponPort port = new UnconnectedCouponPort();

    @Test
    @DisplayName("쿠폰을 잡으려 하면 503으로 거절한다")
    void rejectsReserve() {
        assertThatThrownBy(() -> port.reserve(1L, 2L, 3L))
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("잡은 쿠폰이 없으므로 사용·반환은 아무것도 하지 않고 성공한다")
    void useAndReleaseDoNothing() {
        assertThatCode(() -> {
                    port.use(3L);
                    port.release(3L);
                })
                .doesNotThrowAnyException();
    }
}
