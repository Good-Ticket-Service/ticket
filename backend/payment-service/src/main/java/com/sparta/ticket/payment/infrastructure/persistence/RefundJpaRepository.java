package com.sparta.ticket.payment.infrastructure.persistence;

import com.sparta.ticket.payment.domain.Refund;
import com.sparta.ticket.payment.domain.RefundRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundJpaRepository extends JpaRepository<Refund, Long>, RefundRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.paymentId = :paymentId")
    Optional<Refund> lockByPaymentId(@Param("paymentId") Long paymentId);
}
