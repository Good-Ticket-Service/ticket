package com.sparta.ticket.fakepg.domain.repository;

import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransactionStatus;
import java.util.List;
import java.util.Optional;

public interface PgTransactionRepository {

    PgTransaction saveAndFlush(PgTransaction transaction);

    Optional<PgTransaction> findByPaymentKey(String paymentKey);

    Optional<PgTransaction> findByProviderAndOrderId(PgProvider provider, String orderId);

    /** 행을 잠그고 읽는다. 같은 거래의 승인·취소가 동시에 와도 한 번만 처리한다. */
    Optional<PgTransaction> lockByPaymentKey(String paymentKey);

    Optional<PgTransaction> lockByProviderAndOrderId(PgProvider provider, String orderId);

    List<PgTransaction> search(String orderId, PgTransactionStatus status, int limit);

    void deleteAllTransactions();
}
