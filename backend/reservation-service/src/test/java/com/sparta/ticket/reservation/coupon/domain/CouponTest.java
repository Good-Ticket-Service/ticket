package com.sparta.ticket.reservation.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.exception.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CouponTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final LocalDateTime START = NOW.plusDays(1);
    private static final LocalDateTime END = NOW.plusDays(7);

    /** 발급 시작 전(UPCOMING) 쿠폰. */
    private static Coupon upcoming() {
        return Coupon.create("오픈 기념 5,000원 할인", 5_000, 1_000, START, END, 7, NOW);
    }

    /** 발급 중(ISSUING) 쿠폰. */
    private static Coupon issuing() {
        return Coupon.create("오픈 기념 5,000원 할인", 5_000, 1_000, NOW.minusDays(1), END, 7, NOW);
    }

    private static void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("발급 수량 0으로 만들어지고 남은 수량은 총 수량과 같다")
        void create() {
            Coupon coupon = upcoming();

            assertThat(coupon.getIssuedQuantity()).isZero();
            assertThat(coupon.getRemainingQuantity()).isEqualTo(1_000);
            assertThat(coupon.issueStatus(NOW)).isEqualTo(CouponIssueStatus.UPCOMING);
        }

        @Test
        @DisplayName("발급 종료 시각이 시작 시각보다 늦지 않으면 VALIDATION_ERROR")
        void endNotAfterStart() {
            assertError(
                    () -> Coupon.create("쿠폰", 5_000, 1_000, START, START, 7, NOW), CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("발급 종료 시각이 현재보다 늦지 않으면 VALIDATION_ERROR")
        void endNotAfterNow() {
            assertError(
                    () -> Coupon.create("쿠폰", 5_000, 1_000, NOW.minusDays(2), NOW, 7, NOW),
                    CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("할인액·총 수량·유효 일수가 1보다 작으면 VALIDATION_ERROR")
        void notPositive() {
            assertError(() -> Coupon.create("쿠폰", 0, 1_000, START, END, 7, NOW), CommonErrorCode.VALIDATION_ERROR);
            assertError(() -> Coupon.create("쿠폰", 5_000, 0, START, END, 7, NOW), CommonErrorCode.VALIDATION_ERROR);
            assertError(() -> Coupon.create("쿠폰", 5_000, 1_000, START, END, 0, NOW), CommonErrorCode.VALIDATION_ERROR);
        }
    }

    @Nested
    @DisplayName("발급 상태")
    class IssueStatus {

        @Test
        @DisplayName("시작 시각 정각부터 ISSUING, 종료 시각 정각부터 ENDED")
        void boundaries() {
            Coupon coupon = upcoming();

            assertThat(coupon.issueStatus(START.minusNanos(1_000))).isEqualTo(CouponIssueStatus.UPCOMING);
            assertThat(coupon.issueStatus(START)).isEqualTo(CouponIssueStatus.ISSUING);
            assertThat(coupon.issueStatus(END.minusNanos(1_000))).isEqualTo(CouponIssueStatus.ISSUING);
            assertThat(coupon.issueStatus(END)).isEqualTo(CouponIssueStatus.ENDED);
        }
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("발급 시작 전에는 모든 항목을 바꿀 수 있다")
        void beforeStart() {
            Coupon coupon = upcoming();

            coupon.update("새 이름", 3_000, 500, START.plusDays(1), END.plusDays(1), 14, NOW);

            assertThat(coupon.getName()).isEqualTo("새 이름");
            assertThat(coupon.getDiscountAmount()).isEqualTo(3_000);
            assertThat(coupon.getTotalQuantity()).isEqualTo(500);
            assertThat(coupon.getIssueStartAt()).isEqualTo(START.plusDays(1));
            assertThat(coupon.getIssueEndAt()).isEqualTo(END.plusDays(1));
            assertThat(coupon.getValidDays()).isEqualTo(14);
        }

        @Test
        @DisplayName("null인 항목은 바꾸지 않는다")
        void partial() {
            Coupon coupon = upcoming();

            coupon.update("새 이름", null, null, null, null, null, NOW);

            assertThat(coupon.getName()).isEqualTo("새 이름");
            assertThat(coupon.getDiscountAmount()).isEqualTo(5_000);
            assertThat(coupon.getIssueEndAt()).isEqualTo(END);
        }

        @Test
        @DisplayName("발급 시작 후에도 이름·종료 시각·총 수량은 바꿀 수 있다")
        void afterStartAllowed() {
            Coupon coupon = issuing();

            coupon.update("연장", null, 1_500, null, END.plusDays(7), null, NOW);

            assertThat(coupon.getName()).isEqualTo("연장");
            assertThat(coupon.getTotalQuantity()).isEqualTo(1_500);
            assertThat(coupon.getIssueEndAt()).isEqualTo(END.plusDays(7));
        }

        @Test
        @DisplayName("발급 시작 후 할인액·시작 시각·유효 일수를 다른 값으로 바꾸면 COUPON_NOT_MODIFIABLE")
        void afterStartNotModifiable() {
            Coupon coupon = issuing();

            assertError(
                    () -> coupon.update(null, 3_000, null, null, null, null, NOW),
                    CouponErrorCode.COUPON_NOT_MODIFIABLE);
            assertError(
                    () -> coupon.update(null, null, null, NOW.minusHours(1), null, null, NOW),
                    CouponErrorCode.COUPON_NOT_MODIFIABLE);
            assertError(
                    () -> coupon.update(null, null, null, null, null, 14, NOW), CouponErrorCode.COUPON_NOT_MODIFIABLE);
        }

        @Test
        @DisplayName("발급 시작 후라도 바꿀 수 없는 항목을 지금과 같은 값으로 보내면 허용한다")
        void afterStartSameValue() {
            Coupon coupon = issuing();

            coupon.update("연장", 5_000, null, coupon.getIssueStartAt(), null, 7, NOW);

            assertThat(coupon.getName()).isEqualTo("연장");
        }

        @Test
        @DisplayName("총 수량을 발급한 수량보다 적게 바꾸면 COUPON_QUANTITY_BELOW_ISSUED")
        void quantityBelowIssued() {
            Coupon coupon = issuing();
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 300);

            assertError(
                    () -> coupon.update(null, null, 299, null, null, null, NOW),
                    CouponErrorCode.COUPON_QUANTITY_BELOW_ISSUED);

            coupon.update(null, null, 300, null, null, null, NOW);
            assertThat(coupon.getRemainingQuantity()).isZero();
        }

        @Test
        @DisplayName("발급 종료 시각이 현재나 시작 시각보다 늦지 않으면 VALIDATION_ERROR")
        void invalidEnd() {
            Coupon coupon = upcoming();

            assertError(() -> coupon.update(null, null, null, null, NOW, null, NOW), CommonErrorCode.VALIDATION_ERROR);
            assertError(() -> coupon.update(null, null, null, END, null, null, NOW), CommonErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("검증에 실패하면 어떤 항목도 바뀌지 않는다")
        void atomic() {
            Coupon coupon = issuing();

            assertError(
                    () -> coupon.update("새 이름", null, null, null, NOW, null, NOW), CommonErrorCode.VALIDATION_ERROR);

            assertThat(coupon.getName()).isEqualTo("오픈 기념 5,000원 할인");
        }

        @Test
        @DisplayName("발급을 중단한 쿠폰의 종료 시각을 미래로 바꾸면 COUPON_ALREADY_CLOSED이고 다시 열리지 않는다")
        void closedCannotReopen() {
            Coupon coupon = issuing();
            coupon.close(NOW);
            LocalDateTime later = NOW.plusMinutes(1);

            assertError(
                    () -> coupon.update(null, null, null, null, END, null, later),
                    CouponErrorCode.COUPON_ALREADY_CLOSED);

            assertThat(coupon.getIssueEndAt()).isEqualTo(NOW);
            assertThat(coupon.issueStatus(later)).isEqualTo(CouponIssueStatus.ENDED);
        }

        @Test
        @DisplayName("발급 기간이 지나 끝난 쿠폰도 종료 시각을 바꿀 수 없다")
        void expiredCannotReopen() {
            Coupon coupon = issuing();
            LocalDateTime afterEnd = END.plusDays(1);

            assertError(
                    () -> coupon.update(null, null, null, null, afterEnd.plusDays(7), null, afterEnd),
                    CouponErrorCode.COUPON_ALREADY_CLOSED);
        }

        @Test
        @DisplayName("끝난 쿠폰도 이름·총 수량은 바꿀 수 있다")
        void closedOtherFieldsAllowed() {
            Coupon coupon = issuing();
            coupon.close(NOW);

            coupon.update("종료된 이벤트", null, 2_000, null, null, null, NOW.plusMinutes(1));

            assertThat(coupon.getName()).isEqualTo("종료된 이벤트");
            assertThat(coupon.getTotalQuantity()).isEqualTo(2_000);
            assertThat(coupon.getIssueEndAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("발급 중단")
    class Close {

        @Test
        @DisplayName("발급 중인 쿠폰은 종료 시각을 현재로 당겨 ENDED가 된다")
        void closeIssuing() {
            Coupon coupon = issuing();
            LocalDateTime start = coupon.getIssueStartAt();

            coupon.close(NOW);

            assertThat(coupon.getIssueStartAt()).isEqualTo(start);
            assertThat(coupon.getIssueEndAt()).isEqualTo(NOW);
            assertThat(coupon.issueStatus(NOW)).isEqualTo(CouponIssueStatus.ENDED);
        }

        @Test
        @DisplayName("시작 전 쿠폰은 시작·종료 시각을 모두 현재로 당겨 ENDED가 된다")
        void closeUpcoming() {
            Coupon coupon = upcoming();

            coupon.close(NOW);

            assertThat(coupon.getIssueStartAt()).isEqualTo(NOW);
            assertThat(coupon.getIssueEndAt()).isEqualTo(NOW);
            assertThat(coupon.issueStatus(NOW)).isEqualTo(CouponIssueStatus.ENDED);
        }

        @Test
        @DisplayName("이미 끝난 쿠폰을 중단하면 COUPON_ALREADY_CLOSED")
        void alreadyClosed() {
            Coupon coupon = issuing();
            coupon.close(NOW);

            assertError(() -> coupon.close(NOW.plusMinutes(1)), CouponErrorCode.COUPON_ALREADY_CLOSED);
        }
    }
}
