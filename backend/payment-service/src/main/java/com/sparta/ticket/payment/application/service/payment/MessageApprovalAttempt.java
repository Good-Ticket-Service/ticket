package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 한 전달 건의 순차 재시도에만 사용한다. 영속 실행권은 항상 claim에서 다시 얻는다. */
public final class MessageApprovalAttempt {
    private static final long RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final MessageConfirmCommand command;
    private final LongSupplier ticker;
    private MessageApprovalTransactions.Registration registration;
    private long checkedAt;

    public MessageApprovalAttempt(MessageConfirmCommand command) {
        this(command, System::nanoTime);
    }

    MessageApprovalAttempt(MessageConfirmCommand command, LongSupplier ticker) {
        this.command = Objects.requireNonNull(command);
        this.ticker = Objects.requireNonNull(ticker);
    }

    MessageConfirmCommand command() {
        return command;
    }

    MessageApprovalTransactions.Registration registration(MessageApprovalTransactions transactions, Clock clock) {
        long elapsed = ticker.getAsLong() - checkedAt;
        if (registration == null
                || elapsed < 0
                || elapsed >= RECHECK_NANOS
                || !LocalDateTime.now(clock).isBefore(command.confirmBefore())) {
            // Note: 재확인 실패 뒤에는 이전 결과로 DB 검사를 우회하지 않는다.
            registration = null;
            var current = transactions.register(command);
            if (!current.completed()) {
                registration = current;
                checkedAt = ticker.getAsLong();
            }
            return current;
        }
        return registration;
    }
}
