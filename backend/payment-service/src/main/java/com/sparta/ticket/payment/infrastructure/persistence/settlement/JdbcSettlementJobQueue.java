package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.application.port.settlement.SettlementJobQueue;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJobReason;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 같은 결제·사유가 이미 있으면 충돌 없이 넘어가도록 ON CONFLICT로 저장한다. 실패하면 호출자의 확정도 함께 롤백된다. */
@Component
@RequiredArgsConstructor
public class JdbcSettlementJobQueue implements SettlementJobQueue {
    private final JdbcTemplate jdbc;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long paymentId, SettlementJobReason reason, LocalDateTime now) {
        String actor = auditorAware.getCurrentAuditor().orElse("SYSTEM");
        jdbc.update(
                "INSERT INTO p_settlement_jobs"
                        + " (payment_id,reason,status,attempts,next_attempt_at,created_at,created_by,updated_at,updated_by)"
                        + " VALUES (?,?,'PENDING',0,?,?,?,?,?) ON CONFLICT (payment_id, reason) DO NOTHING",
                paymentId,
                reason.name(),
                now,
                now,
                actor,
                now,
                actor);
    }
}
