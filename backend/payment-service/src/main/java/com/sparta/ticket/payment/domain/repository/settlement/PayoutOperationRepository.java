package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.PayoutOperationCommand;
import java.util.List;
import java.util.Optional;

public interface PayoutOperationRepository {

    Optional<PayoutOperationCommand> findById(String commandId);

    PayoutOperationCommand saveAndFlush(PayoutOperationCommand command);

    List<PayoutOperationCommand> findFirst20ByPayoutIdOrderByProcessedAtDescCommandIdDesc(Long payoutId);
}
