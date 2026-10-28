package com.sparta.ticket.payment.domain.repository.refund;

import com.sparta.ticket.payment.domain.model.refund.RefundOperationCommand;
import java.util.List;
import java.util.Optional;

public interface RefundOperationRepository {
    Optional<RefundOperationCommand> findById(String commandId);

    RefundOperationCommand saveAndFlush(RefundOperationCommand command);

    List<RefundOperationCommand> findFirst20ByRefundIdOrderByProcessedAtDescCommandIdDesc(Long refundId);
}
