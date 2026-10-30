package com.sparta.ticket.fakepg.application.service.payout;

import com.sparta.ticket.fakepg.domain.model.payout.PgPayout;
import com.sparta.ticket.fakepg.domain.repository.PgPayoutRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 지급대행 접수·조회 트랜잭션. 같은 멱등 키는 UNIQUE로 한 건만 접수된다. */
@Component
public class PgPayoutTransactions {

    private final PgPayoutRepository repository;
    private final Clock clock;
    private final long processingDelayMs;

    public PgPayoutTransactions(
            PgPayoutRepository repository,
            Clock clock,
            @Value("${fake-pg.payout.processing-delay-ms:3000}") long processingDelayMs) {
        this.repository = repository;
        this.clock = clock;
        this.processingDelayMs = processingDelayMs;
    }

    /** 같은 키가 동시에 오면 하나만 커밋되고 나머지는 UNIQUE 위반으로 끝난다. 호출자가 다시 조회해 기존 결과를 돌려준다. */
    @Transactional
    public PgPayout accept(PayoutCommand command, String requestHash, boolean rejected) {
        return repository.saveAndFlush(PgPayout.accept(
                command.idempotencyKey(),
                requestHash,
                command.sellerRef(),
                command.accountRef(),
                command.holderMasked(),
                command.amount(),
                rejected,
                LocalDateTime.now(clock),
                processingDelayMs));
    }

    /** 조회 시점의 상태로 확정해 저장한다. 처리 시각이 지났으면 접수 때 정한 결과가 된다. */
    @Transactional
    public Optional<PgPayout> observe(String idempotencyKey) {
        Optional<PgPayout> payout = repository.lockByIdempotencyKey(idempotencyKey);
        payout.ifPresent(found -> found.observe(LocalDateTime.now(clock)));
        return payout;
    }
}
