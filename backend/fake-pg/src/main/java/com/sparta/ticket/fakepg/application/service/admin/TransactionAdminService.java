package com.sparta.ticket.fakepg.application.service.admin;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransactionStatus;
import com.sparta.ticket.fakepg.domain.repository.IdempotencyRecordRepository;
import com.sparta.ticket.fakepg.domain.repository.PgTransactionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 대사·정합성 검사용 거래 조회, 우리 API를 거치지 않은 외부 변경 재현, 실험 사이에 데이터를 비우는 기능. */
@Service
@RequiredArgsConstructor
public class TransactionAdminService {

    private static final int MAX_LIMIT = 1000;

    private final PgTransactionRepository transactions;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final Clock clock;

    public List<PgTransaction> search(String orderId, PgTransactionStatus status, int limit) {
        return transactions.search(orderId, status, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    /** 가맹점 관리자 화면 등에서 취소된 상황을 만든다. cancelAmount가 null이면 전액, 승인액보다 작으면 부분 취소다. */
    @Transactional
    public PgTransaction cancelExternally(PgProvider provider, String orderId, Integer cancelAmount, String reason) {
        PgTransaction transaction = transactions
                .lockByProviderAndOrderId(provider, orderId)
                .orElseThrow(() -> new PgException(PgErrorCode.NOT_FOUND_PAYMENT));
        transaction.cancelExternally(cancelAmount, reason, LocalDateTime.now(clock));
        return transactions.saveAndFlush(transaction);
    }

    public void reset() {
        idempotencyRecords.deleteAllRecords();
        transactions.deleteAllTransactions();
    }
}
