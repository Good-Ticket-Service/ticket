package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SellerPayoutAccount;
import com.sparta.ticket.payment.domain.repository.settlement.SellerPayoutAccountRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerPayoutAccountJpaRepository
        extends JpaRepository<SellerPayoutAccount, Long>, SellerPayoutAccountRepository {

    @Override
    Optional<SellerPayoutAccount> findBySellerId(Long sellerId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SellerPayoutAccount a WHERE a.sellerId = :sellerId")
    Optional<SellerPayoutAccount> lockBySellerId(@Param("sellerId") Long sellerId);
}
