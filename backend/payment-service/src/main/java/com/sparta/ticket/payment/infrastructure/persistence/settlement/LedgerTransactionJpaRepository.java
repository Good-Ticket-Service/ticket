package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.LedgerAction;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import com.sparta.ticket.payment.domain.repository.settlement.LedgerTransactionRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionJpaRepository
        extends JpaRepository<LedgerTransaction, Long>, LedgerTransactionRepository {

    @Override
    @Query("SELECT t FROM LedgerTransaction t LEFT JOIN FETCH t.entries"
            + " WHERE t.sourceType = :sourceType AND t.sourceId = :sourceId AND t.action = :action")
    Optional<LedgerTransaction> findByKey(
            @Param("sourceType") LedgerSourceType sourceType,
            @Param("sourceId") Long sourceId,
            @Param("action") LedgerAction action);
}
