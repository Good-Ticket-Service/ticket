package com.sparta.ticket.payment.domain.model.refund;

import static com.sparta.ticket.payment.support.PaymentFixture.NOW;
import static com.sparta.ticket.payment.support.PaymentFixture.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundTest {

    private static final Payment PAYMENT = Payment.ready(order("order-1", 1L));

    private static Refund requested() {
        return Refund.requested(PAYMENT, RefundReason.USER_CANCEL, PAYMENT.getAmount(), NOW, NOW);
    }

    private static void assertInvalidTransition(Runnable transition) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("REQUESTED는 요청 시각과 복구 시각을 기록한다")
    void requestedRecordsTimes() {
        Refund refund = requested();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getRequestedAt()).isEqualTo(NOW);
        assertThat(refund.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(refund.getRefundAmount()).isEqualTo(45_000);
    }

    @Test
    @DisplayName("ensureRequested는 WAITING·SKIPPED만 REQUESTED로 올리고 처음 사유를 유지한다")
    void ensureRequestedUpgradesWaitingAndSkipped() {
        Refund waiting = Refund.waiting(PAYMENT, RefundReason.USER_CANCEL);
        Refund skipped = Refund.waiting(PAYMENT, RefundReason.USER_CANCEL);
        skipped.skip();

        assertThat(waiting.ensureRequested(44_000, NOW, NOW)).isTrue();
        assertThat(skipped.ensureRequested(44_000, NOW, NOW)).isTrue();
        assertThat(waiting.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(waiting.getReason()).isEqualTo(RefundReason.USER_CANCEL);
        assertThat(skipped.getRefundAmount()).isEqualTo(44_000);
    }

    @Test
    @DisplayName("ensureRequested는 이미 REQUESTED·DONE·FAILED인 환불을 바꾸지 않는다")
    void ensureRequestedKeepsOthers() {
        Refund refund = requested();
        Refund done = requested();
        done.complete("cancel-1", NOW);
        Refund failed = requested();
        failed.reject("NOT_CANCELABLE", "취소 불가");

        assertThat(refund.ensureRequested(1_000, NOW, NOW)).isFalse();
        assertThat(done.ensureRequested(1_000, NOW, NOW)).isFalse();
        assertThat(failed.ensureRequested(1_000, NOW, NOW)).isFalse();
        assertThat(refund.getRefundAmount()).isEqualTo(45_000);
    }

    @Test
    @DisplayName("업무 거절은 FAILED + 차단이고, 관리자 재시도는 키 세대를 올려 REQUESTED로 돌린다")
    void rejectAndRetryWithNewKey() {
        Refund refund = requested();
        refund.reject("NOT_CANCELABLE", "취소 불가");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.isRecoveryBlocked()).isTrue();
        assertThat(refund.pgCancelKey("order-1")).isEqualTo("order-1:cancel:0");

        refund.retryByAdmin(NOW.plusHours(1));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.isRecoveryBlocked()).isFalse();
        assertThat(refund.getNextAttemptAt()).isEqualTo(NOW.plusHours(1));
        assertThat(refund.pgCancelKey("order-1")).isEqualTo("order-1:cancel:1");
        assertThat(refund.getRequestedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("운영 중단 해제는 조회 전용으로 재개하고 원래 시각과 취소 키를 보존한다")
    void blockAndUnblockPreservesClockAndKey() {
        Refund refund = requested();
        refund.blockRecovery("GIVE_UP");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.isRecoveryBlocked()).isTrue();
        assertThat(refund.getLastError()).isEqualTo("GIVE_UP");

        refund.unblockRecovery(NOW.plusHours(2));

        assertThat(refund.isRecoveryBlocked()).isFalse();
        assertThat(refund.getRecoveryStartedAt()).isEqualTo(NOW);
        assertThat(refund.getRequestedAt()).isEqualTo(NOW);
        assertThat(refund.getCancelKeyCreatedAt()).isEqualTo(NOW);
        assertThat(refund.getRecoveryMode()).isEqualTo(RefundRecoveryMode.VERIFY_ONLY);
        assertThat(refund.canCancelAt(NOW.plusHours(2), java.time.Duration.ofHours(24)))
                .isFalse();
        assertThat(refund.getNextAttemptAt()).isEqualTo(NOW.plusHours(2));
    }

    @Test
    @DisplayName("차단되지 않은 환불은 차단 해제할 수 없다")
    void unblockOnlyWhenBlocked() {
        assertInvalidTransition(() -> requested().unblockRecovery(NOW));
    }

    @Test
    @DisplayName("상태표에 없는 환불 전이는 막는다")
    void rejectsInvalidTransitions() {
        Refund waiting = Refund.waiting(PAYMENT, RefundReason.USER_CANCEL);
        Refund done = requested();
        done.complete("cancel-1", NOW);

        assertInvalidTransition(() -> waiting.complete("cancel-1", NOW));
        assertInvalidTransition(() -> waiting.reject("X", null));
        assertInvalidTransition(() -> done.skip());
        assertInvalidTransition(() -> done.retryByAdmin(NOW));
        assertInvalidTransition(() -> requested().skip());
        assertInvalidTransition(() -> requested().retryByAdmin(NOW));
    }
}
