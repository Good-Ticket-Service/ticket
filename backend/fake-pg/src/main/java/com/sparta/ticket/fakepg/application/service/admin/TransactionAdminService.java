package com.sparta.ticket.fakepg.application.service.admin;

import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransactionStatus;
import com.sparta.ticket.fakepg.domain.repository.IdempotencyRecordRepository;
import com.sparta.ticket.fakepg.domain.repository.PgTransactionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 대사·정합성 검사용 거래 조회와, 실험 사이에 데이터를 비우는 기능. */
@Service
@RequiredArgsConstructor
public class TransactionAdminService {

    private static final int MAX_LIMIT = 1000;

    private final PgTransactionRepository transactions;
    private final IdempotencyRecordRepository idempotencyRecords;

    public List<PgTransaction> search(String orderId, PgTransactionStatus status, int limit) {
        return transactions.search(orderId, status, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    public void reset() {
        idempotencyRecords.deleteAllRecords();
        transactions.deleteAllTransactions();
    }
}
