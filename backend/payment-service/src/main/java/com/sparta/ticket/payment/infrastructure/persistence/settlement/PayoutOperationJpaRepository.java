package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.PayoutOperationCommand;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutOperationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutOperationJpaRepository
        extends JpaRepository<PayoutOperationCommand, String>, PayoutOperationRepository {}
