package com.sparta.ticket.payment.domain.model.payment;

import static com.sparta.ticket.payment.support.PaymentFixture.NOW;
import static com.sparta.ticket.payment.support.PaymentFixture.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Duration FIRST_CHECK = Duration.ofSeconds(10);
    private static final Duration RESULT_DEADLINE = Duration.ofMinutes(1);

    private static Payment ready() {
        return Payment.ready(order("order-1", 1L));
    }

    private static Payment inProgress() {
        Payment payment = ready();
        payment.startApproval("payment-key", NOW, FIRST_CHECK);
        return payment;
    }

    private static Payment done() {
        Payment payment = inProgress();
        payment.approve("payment-key", NOW.plusSeconds(1));
        return payment;
    }

    private static Payment failed() {
        Payment payment = inProgress();
        payment.fail("REJECT_CARD_PAYMENT", "한도 초과");
        return payment;
    }

    private static void assertInvalidTransition(Runnable transition) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Nested
    @DisplayName("준비")
    class Ready {

        @Test
        @DisplayName("할인 후 금액으로 READY가 된다")
        void readyWithDiscountedAmount() {
            Payment payment = ready();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(payment.getAmount()).isEqualTo(45_000);
            assertThat(payment.getDiscountAmount()).isEqualTo(5_000);
        }

        @Test
        @DisplayName("할인 후 금액이 100원 미만이거나 할인액이 음수면 만들 수 없다")
        void rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> Payment.ready(order("order-1", 1L, 50_000, 50_000)))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> Payment.ready(order("order-1", 1L, 50_000, -1)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("최소 금액 100원은 허용하고 99원과 초과 할인은 거절한다")
        void minimumAmountBoundary() {
            assertThat(Payment.ready(order("min", 1L, 1_000, 900)).getAmount()).isEqualTo(100);
            assertThat(Payment.ready(order("no-discount", 1L, 100, 0)).getAmount())
                    .isEqualTo(100);
            assertThatThrownBy(() -> Payment.ready(order("below-min", 1L, 1_000, 901)))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> Payment.ready(order("excess", 1L, 1_000, 1_001)))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> Payment.ready(order("overflow", 1L, Integer.MIN_VALUE, 1)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("같은 내용의 준비 요청만 일치한다")
        void matchesSameOrderOnly() {
            Payment payment = ready();

            assertThat(payment.matches(order("order-1", 1L))).isTrue();
            assertThat(payment.matches(order("order-1", 2L))).isFalse();
            assertThat(payment.matches(order("order-1", 1L, 50_000, 4_999))).isFalse();
        }

        @Test
        @DisplayName("만료 시각은 DB 정밀도(마이크로초)로 저장하고 같은 정밀도로 비교한다")
        void comparesExpiryAtDbPrecision() {
            LocalDateTime expiresAt = NOW.plusMinutes(10).plusNanos(123_456_789);
            Payment payment = Payment.ready(order("order-1", 1L, expiresAt));

            assertThat(payment.getExpiresAt()).isEqualTo(NOW.plusMinutes(10).plusNanos(123_456_000));
            assertThat(payment.matches(order("order-1", 1L, expiresAt))).isTrue();
            assertThat(payment.matches(order("order-1", 1L, expiresAt.plusNanos(1_000))))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("허용된 전이")
    class AllowedTransitions {

        @Test
        @DisplayName("READY → IN_PROGRESS는 승인 호출 시각과 첫 복구 시각을 기록한다")
        void startApproval() {
            Payment payment = inProgress();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(payment.getPgTransactionId()).isEqualTo("payment-key");
            assertThat(payment.getApproveRequestedAt()).isEqualTo(NOW);
            assertThat(payment.getNextAttemptAt()).isEqualTo(NOW.plus(FIRST_CHECK));
            assertThat(payment.getRecoveryStartedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("IN_PROGRESS → DONE이면 복구 대상에서 빠진다")
        void approve() {
            Payment payment = done();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(payment.getNextAttemptAt()).isNull();
        }

        @Test
        @DisplayName("READY와 IN_PROGRESS는 FAILED가 될 수 있다")
        void failFromReadyOrInProgress() {
            Payment fromReady = ready();
            fromReady.fail(PaymentFailureCode.EXPIRED, null);

            assertThat(fromReady.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(fromReady.getFailureCode()).isEqualTo("EXPIRED");
            assertThat(failed().getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("DONE → CANCELED")
        void cancel() {
            Payment payment = done();
            payment.cancel();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("승인 요청 접수 시각은 처음 것만 남는다")
        void keepsFirstConfirmRequest() {
            Payment payment = ready();
            payment.requestConfirm(NOW);
            payment.requestConfirm(NOW.plusMinutes(1));

            assertThat(payment.getConfirmRequestedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("막힌 전이")
    class RejectedTransitions {

        @Test
        @DisplayName("READY가 아니면 승인을 시작할 수 없다 (중복 승인 요청이 PG를 두 번 부르지 않는다)")
        void startApprovalOnlyFromReady() {
            assertInvalidTransition(() -> inProgress().startApproval("key", NOW, FIRST_CHECK));
            assertInvalidTransition(() -> done().startApproval("key", NOW, FIRST_CHECK));
            assertInvalidTransition(() -> failed().startApproval("key", NOW, FIRST_CHECK));
        }

        @Test
        @DisplayName("IN_PROGRESS가 아니면 DONE이 될 수 없다 (늦은 승인이 FAILED를 되살리지 않는다)")
        void approveOnlyFromInProgress() {
            assertInvalidTransition(() -> ready().approve("key", NOW));
            assertInvalidTransition(() -> failed().approve("key", NOW));
        }

        @Test
        @DisplayName("끝난 결제는 FAILED가 될 수 없다")
        void failOnlyBeforeFinal() {
            assertInvalidTransition(() -> done().fail(PaymentFailureCode.PG_UNAVAILABLE, null));
            assertInvalidTransition(() -> failed().fail(PaymentFailureCode.PG_UNAVAILABLE, null));
        }

        @Test
        @DisplayName("DONE이 아니면 CANCELED가 될 수 없다")
        void cancelOnlyFromDone() {
            assertInvalidTransition(() -> ready().cancel());
            assertInvalidTransition(() -> inProgress().cancel());
            assertInvalidTransition(() -> failed().cancel());
        }

        @Test
        @DisplayName("READY가 아니면 승인 요청 접수와 준비 결과 기록을 할 수 없다")
        void confirmAndCheckoutOnlyOnReady() {
            assertInvalidTransition(() -> inProgress().requestConfirm(NOW));
            assertInvalidTransition(() -> inProgress().recordCheckout("tid", "url"));
        }
    }

    @Nested
    @DisplayName("복구와 대사")
    class RecoveryAndReconciliation {

        @Test
        @DisplayName("결과를 모르면 시도 횟수와 다음 시각을 남긴다")
        void deferRecordsAttempt() {
            Payment payment = inProgress();
            payment.deferRecovery("READ_TIMEOUT", NOW.plusSeconds(10));

            assertThat(payment.getRecoveryAttempts()).isEqualTo(1);
            assertThat(payment.getLastError()).isEqualTo("READ_TIMEOUT");
            assertThat(payment.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        }

        @Test
        @DisplayName("결과 확정 시한은 승인 호출 후 1분 직전에는 지나지 않았고 정확히 1분부터 지났다")
        void resultDeadlineBoundary() {
            Payment payment = inProgress();

            assertThat(payment.isResultDeadlineOver(NOW.plusSeconds(59), RESULT_DEADLINE))
                    .isFalse();
            assertThat(payment.isResultDeadlineOver(NOW.plusSeconds(60), RESULT_DEADLINE))
                    .isTrue();
        }

        @Test
        @DisplayName("결과 확정 시한은 IN_PROGRESS 결제에만 적용한다")
        void resultDeadlineOnlyInProgress() {
            assertThat(ready().isResultDeadlineOver(NOW.plusHours(1), RESULT_DEADLINE))
                    .isFalse();
            assertThat(done().isResultDeadlineOver(NOW.plusHours(1), RESULT_DEADLINE))
                    .isFalse();
        }

        @Test
        @DisplayName("시한이 지나 PG_UNRESOLVED로 끝낸 결제는 최소 대사 대상이다")
        void unresolvedFailureNeedsReconciliation() {
            Payment payment = inProgress();
            payment.fail(PaymentFailureCode.PG_UNRESOLVED, "결과 확정 시한 경과");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("PG_UNRESOLVED");
            assertThat(payment.getNextAttemptAt()).isNull();
            assertThat(payment.needsReconciliation()).isTrue();
        }

        @Test
        @DisplayName("승인 기록 없음은 승인 호출 후 유예 시간이 지나야 확정할 수 있다")
        void notFoundGrace() {
            Payment payment = inProgress();

            assertThat(payment.isNotFoundGraceOver(NOW.plusSeconds(59), Duration.ofMinutes(1)))
                    .isFalse();
            assertThat(payment.isNotFoundGraceOver(NOW.plusSeconds(60), Duration.ofMinutes(1)))
                    .isTrue();
        }

        @Test
        @DisplayName("최소 대사는 PG 승인을 불렀던 FAILED 결제만 한 번 본다")
        void reconcileOnlyFailedAfterApprovalCall() {
            Payment expiredBeforeApproval = ready();
            expiredBeforeApproval.fail(PaymentFailureCode.EXPIRED, null);
            Payment failedAfterApproval = failed();

            assertThat(expiredBeforeApproval.needsReconciliation()).isFalse();
            assertThat(failedAfterApproval.needsReconciliation()).isTrue();

            failedAfterApproval.markReconciled(NOW.plusHours(1));

            assertThat(failedAfterApproval.needsReconciliation()).isFalse();
            assertInvalidTransition(() -> failedAfterApproval.markReconciled(NOW.plusHours(2)));
        }
    }
}
