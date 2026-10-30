package com.sparta.ticket.payment.application.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.domain.model.settlement.SaleSource;
import com.sparta.ticket.payment.domain.model.settlement.SettlementFailure;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItemStatus;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 포착 OFF와 계산 실패 항목의 환불 보류처럼 DB 없이 판단할 수 있는 경우만 본다. */
class SettlementCaptureTransactionsTest {

    private static final SaleSource SOURCE =
            new SaleSource(1L, 2L, 3L, 4L, null, LocalDateTime.of(2026, 11, 2, 10, 0), 10_000, 0, 0, 9_000);

    @Test
    void captureOffTouchesNothing() {
        SettlementItemRepository items = mock(SettlementItemRepository.class);
        LedgerTransactions ledger = mock(LedgerTransactions.class);
        SettlementCaptureTransactions capture = new SettlementCaptureTransactions(
                mock(SettlementRules.class), items, ledger, mock(CommittedObservation.class), false);

        capture.captureApproval(SOURCE, 7L);

        verifyNoInteractions(items, ledger);
    }

    @Test
    void unpostedSaleKeepsStatusAndRecordsRefund() {
        SettlementItem unposted = SettlementItem.unpostedSale(SOURCE, SettlementFailure.PAID_AMOUNT_MISMATCH);

        unposted.holdForRefund(7L);

        assertThat(unposted.getStatus()).isEqualTo(SettlementItemStatus.UNPOSTED);
        assertThat(unposted.getRefundId()).isEqualTo(7L);
        assertThat(unposted.getHoldReason()).isNull();
    }
}
