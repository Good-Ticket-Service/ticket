package com.sparta.ticket.payment.infrastructure.persistence.refund;

import com.sparta.ticket.payment.domain.model.refund.RefundOperationCommand;
import com.sparta.ticket.payment.domain.repository.refund.RefundOperationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundOperationJpaRepository
        extends JpaRepository<RefundOperationCommand, String>, RefundOperationRepository {}
