package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.port.settlement.SettlementPreviewReader;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementPreviewTransactions {
    private final SettlementPreviewReader reader;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SettlementPreviewReader.Preview read(
            long sellerId, Long scheduleId, LocalDate from, LocalDate to, int page, int size) {
        if (sellerId <= 0
                || scheduleId != null && scheduleId <= 0
                || from == null
                || to == null
                || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= 31
                || page < 0
                || page > 10000
                || size < 1
                || size > 100) throw new BusinessException(CommonErrorCode.VALIDATION_ERROR);
        return reader.read(sellerId, scheduleId, from, to, page, size);
    }
}
