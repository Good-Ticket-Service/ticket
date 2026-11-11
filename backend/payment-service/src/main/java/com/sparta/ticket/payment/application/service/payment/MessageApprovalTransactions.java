package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.payment.ApprovalClaim;
import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MessageApprovalTransactions {
    private final PaymentRepository payments;
    private final PaymentTransactions results;
    private final Clock clock;

    @Value("${payment.recovery.check-interval-seconds:10}")
    private long checkIntervalSeconds;

    public record Registration(PgProvider provider, boolean completed) {}

    @Transactional
    public Registration register(MessageConfirmCommand command) {
        Payment payment = locked(command);
        validate(payment, command);
        if (payment.getStatus() != PaymentStatus.READY) {
            requireRecoveryEvidence(payment);
            return new Registration(payment.getPgProvider(), true);
        }
        payment.registerMessageConfirm(command.confirmBefore(), command.fingerprint(), LocalDateTime.now(clock));
        if (expired(command)) {
            results.failForRecovery(payment, PaymentFailureCode.EXPIRED);
            return new Registration(payment.getPgProvider(), true);
        }
        return new Registration(payment.getPgProvider(), false);
    }

    @Transactional
    public Optional<ApprovalClaim> claim(MessageConfirmCommand command) {
        Payment payment = locked(command);
        validate(payment, command);
        if (payment.getStatus() != PaymentStatus.READY) {
            requireRecoveryEvidence(payment);
            return Optional.empty();
        }
        payment.registerMessageConfirm(command.confirmBefore(), command.fingerprint(), LocalDateTime.now(clock));
        if (expired(command)) {
            results.failForRecovery(payment, PaymentFailureCode.EXPIRED);
            return Optional.empty();
        }
        if (payment.needsPgReady()
                || payment.getPgProvider() == PgProvider.TOSS
                        && command.pgAuthToken().length() > 200) {
            results.failForRecovery(payment, PaymentFailureCode.INVALID_AUTH_TOKEN);
            return Optional.empty();
        }
        if (payment.getPgProvider() == PgProvider.TOSS) {
            payments.lockTransactionKey("TOSS:" + command.pgAuthToken());
            if (expired(command)) {
                results.failForRecovery(payment, PaymentFailureCode.EXPIRED);
                return Optional.empty();
            }
            if (payments.isPgTransactionInUse("TOSS", command.pgAuthToken())) {
                results.failForRecovery(payment, PaymentFailureCode.INVALID_AUTH_TOKEN);
                return Optional.empty();
            }
        }
        payment.startApproval(
                payment.getPgProvider() == PgProvider.TOSS ? command.pgAuthToken() : null,
                LocalDateTime.now(clock),
                Duration.ofSeconds(checkIntervalSeconds));
        payments.saveAndFlush(payment);
        return Optional.of(new ApprovalClaim(
                payment.getId(),
                payment.getOrderId(),
                payment.getPgProvider(),
                payment.getPgTransactionId(),
                payment.getAmount(),
                payment.getUserId()));
    }

    /** claim 커밋 뒤 시한을 다시 검사한다. 결과 저장 실패 시 caller는 ACK하지 않는다. */
    @Transactional
    public boolean maySend(MessageConfirmCommand command) {
        Payment payment = locked(command);
        validate(payment, command);
        if (payment.getStatus() != PaymentStatus.IN_PROGRESS) return false;
        if (expired(command)) {
            results.failForRecovery(payment, PaymentFailureCode.EXPIRED);
            return false;
        }
        return true;
    }

    @Transactional(readOnly = true)
    public void confirmRecovery(MessageConfirmCommand command) {
        Payment payment = payments.findByOrderId(command.orderId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        validate(payment, command);
        requireRecoveryEvidence(payment);
        if (payment.getStatus() == PaymentStatus.READY) throw new IllegalStateException("승인 책임이 인수되지 않았다");
    }

    private static void requireRecoveryEvidence(Payment payment) {
        if (payment.getStatus() == PaymentStatus.IN_PROGRESS
                && (payment.getApproveRequestedAt() == null
                        || payment.getNextAttemptAt() == null
                        || payment.getPgTransactionId() == null)) throw new IllegalStateException("승인 조회 복구 근거가 없다");
    }

    private Payment locked(MessageConfirmCommand command) {
        return payments.lockByOrderId(command.orderId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private static void validate(Payment payment, MessageConfirmCommand command) {
        if (!Objects.equals(payment.getReservationId(), command.reservationId())
                || payment.getAmount() != command.amount()
                || payment.getMessageConfirmFingerprint() != null
                        && !payment.getMessageConfirmFingerprint().equals(command.fingerprint()))
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_CONFLICT);
    }

    private boolean expired(MessageConfirmCommand command) {
        return !LocalDateTime.now(clock).isBefore(command.confirmBefore());
    }
}
