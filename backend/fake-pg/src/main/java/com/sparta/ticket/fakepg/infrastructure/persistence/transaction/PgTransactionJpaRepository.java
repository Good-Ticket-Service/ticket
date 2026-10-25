package com.sparta.ticket.fakepg.infrastructure.persistence.transaction;

import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransactionStatus;
import com.sparta.ticket.fakepg.domain.repository.PgTransactionRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PgTransactionJpaRepository extends JpaRepository<PgTransaction, Long>, PgTransactionRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PgTransaction t WHERE t.paymentKey = :paymentKey")
    Optional<PgTransaction> lockByPaymentKey(@Param("paymentKey") String paymentKey);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PgTransaction t WHERE t.provider = :provider AND t.orderId = :orderId")
    Optional<PgTransaction> lockByProviderAndOrderId(
            @Param("provider") PgProvider provider, @Param("orderId") String orderId);

    List<PgTransaction> findAllByOrderByIdDesc(Pageable pageable);

    List<PgTransaction> findByOrderIdOrderByIdDesc(String orderId, Pageable pageable);

    List<PgTransaction> findByStatusOrderByIdDesc(PgTransactionStatus status, Pageable pageable);

    List<PgTransaction> findByOrderIdAndStatusOrderByIdDesc(
            String orderId, PgTransactionStatus status, Pageable pageable);

    @Override
    default List<PgTransaction> search(String orderId, PgTransactionStatus status, int limit) {
        Pageable page = PageRequest.of(0, limit);
        if (orderId != null && status != null) {
            return findByOrderIdAndStatusOrderByIdDesc(orderId, status, page);
        }
        if (orderId != null) {
            return findByOrderIdOrderByIdDesc(orderId, page);
        }
        if (status != null) {
            return findByStatusOrderByIdDesc(status, page);
        }
        return findAllByOrderByIdDesc(page);
    }

    @Override
    @Transactional
    @Modifying
    @Query("DELETE FROM PgTransaction")
    void deleteAllTransactions();
}
