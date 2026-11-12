package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sparta.ticket.payment.application.dto.payment.ApprovalClaim;
import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.support.MutablePaymentClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageApprovalWaitTest {
    private final MessageApprovalTransactions transactions = mock(MessageApprovalTransactions.class);
    private final PaymentTransactions results = mock(PaymentTransactions.class);
    private final PgClientRegistry clients = mock(PgClientRegistry.class);
    private final PgClient client = mock(PgClient.class);
    private final MutablePaymentClock clock = new MutablePaymentClock();
    private final AtomicLong nanos = new AtomicLong();
    private MessageApprovalService service;
    private MessageConfirmCommand command;
    private MessageApprovalAttempt attempt;

    @BeforeEach
    void setUp() {
        service = new MessageApprovalService(transactions, results, clients, clock, new MessageApprovalProperties());
        command = new MessageConfirmCommand(
                "waiting", 377L, 9000, LocalDateTime.now(clock).plusMinutes(5), "fixture");
        attempt = new MessageApprovalAttempt(command, nanos::get);
        when(transactions.register(command))
                .thenReturn(new MessageApprovalTransactions.Registration(PgProvider.TOSS, false));
        when(clients.get(PgProvider.TOSS)).thenReturn(client);
        when(client.reserveApproval()).thenReturn(Optional.empty());
    }

    @Test
    void waitingReusesOnlyCommittedRegistrationAndRechecksAtExactlyOneSecond() {
        for (int i = 0; i < 5; i++) {
            nanos.set(i * 200_000_000L);
            assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        }
        verify(transactions).register(command);
        nanos.set(1_000_000_000L);
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        verify(transactions, times(2)).register(command);
        verify(transactions, never()).claim(any());
    }

    @Test
    void lostOwnershipDoesNotUseCachedReceiptOrCallDatabaseOrPg() {
        service.advance(attempt, () -> true);
        assertThat(service.advance(attempt, () -> false)).isEqualTo(MessageApprovalService.Outcome.CANCELED);
        verify(transactions).register(command);
        verify(client).reserveApproval();
    }

    @Test
    void businessDeadlineForcesRecheckEvenWhenMonotonicTimeHasNotAdvanced() {
        service.advance(attempt, () -> true);
        clock.advance(Duration.ofMinutes(5));
        when(transactions.register(command))
                .thenReturn(new MessageApprovalTransactions.Registration(PgProvider.TOSS, true));
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        verify(transactions, times(2)).register(command);
        verify(client).reserveApproval();
    }

    @Test
    void failedRefreshCannotFallBackToPreviousReceipt() {
        service.advance(attempt, () -> true);
        nanos.set(1_000_000_000L);
        when(transactions.register(command))
                .thenThrow(new IllegalStateException("등록 실패"))
                .thenReturn(new MessageApprovalTransactions.Registration(PgProvider.TOSS, false));
        assertThatThrownBy(() -> service.advance(attempt, () -> true)).isInstanceOf(IllegalStateException.class);
        service.advance(attempt, () -> true);
        verify(transactions, times(3)).register(command);
    }

    @Test
    void newDeliveryAndBackwardsTickerRequireDatabaseRegistration() {
        service.advance(attempt, () -> true);
        nanos.set(-1);
        service.advance(attempt, () -> true);
        service.advance(new MessageApprovalAttempt(command, nanos::get), () -> true);
        verify(transactions, times(3)).register(command);
    }

    @Test
    void anotherProcessorCompletionIsFoundAtRefreshWithoutTakingPgPermit() {
        service.advance(attempt, () -> true);
        when(transactions.register(command))
                .thenReturn(new MessageApprovalTransactions.Registration(PgProvider.TOSS, true));
        nanos.set(999_999_999L);
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.RETRY);
        nanos.incrementAndGet();
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        verify(client, times(2)).reserveApproval();
        verify(transactions, never()).claim(any());
    }

    @Test
    void cachedRegistrationNeverBypassesClaimAndProviderValidation() {
        service.advance(attempt, () -> true);
        var permit = mock(PgClient.Approval.class);
        when(client.reserveApproval()).thenReturn(Optional.of(permit));
        when(transactions.claim(command))
                .thenReturn(Optional.of(new ApprovalClaim(1L, "waiting", PgProvider.KAKAO, "tid", 9000, 1L)));
        assertThatThrownBy(() -> service.advance(attempt, () -> true)).isInstanceOf(IllegalStateException.class);
        verify(transactions).register(command);
        verify(permit, never()).approve(any());
        verify(permit).close();
    }

    @Test
    void expiryBetweenFinalDatabaseCheckAndSendDoesNotReachPg() {
        var permit = mock(PgClient.Approval.class);
        when(client.reserveApproval()).thenReturn(Optional.of(permit));
        when(transactions.claim(command))
                .thenReturn(Optional.of(new ApprovalClaim(1L, "waiting", PgProvider.TOSS, "tid", 9000, 1L)));
        when(transactions.maySend(command)).thenAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(5));
            return true;
        });
        assertThat(service.advance(attempt, () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        verify(transactions, times(2)).maySend(command);
        verify(permit, never()).approve(any());
        verify(results, never()).complete(any(), any());
    }
}
