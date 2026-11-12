package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.application.port.settlement.SettlementJobQueue;
import com.sparta.ticket.payment.application.service.payment.SettlementPostingProperties;
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
    private final SettlementPostingProperties properties;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long paymentId, SettlementJobReason reason, LocalDateTime now) {
        String actor = auditorAware.getCurrentAuditor().orElse("SYSTEM");
        jdbc.update(
                """
                WITH input AS (
                  SELECT ?::bigint AS payment_id,?::varchar AS reason,?::timestamp AS occurred_at,
                    ?::varchar AS actor,?::varchar AS transport
                ), event AS (
                  SELECT nextval(pg_get_serial_sequence('p_settlement_jobs','id')) AS id,
                    gen_random_uuid() AS event_id,input.* FROM input
                )
                INSERT INTO p_settlement_jobs(id,payment_id,reason,status,attempts,next_attempt_at,
                  created_at,created_by,updated_at,updated_by,transport,event_id,event_payload)
                SELECT id,payment_id,reason,'PENDING',0,occurred_at,occurred_at,actor,occurred_at,actor,
                  transport,event_id,jsonb_build_object('version',1,'eventId',event_id::text,
                    'jobId',id,'paymentId',payment_id,'reason',reason,'occurredAt',occurred_at)::text
                FROM event ON CONFLICT(payment_id,reason) DO NOTHING
                """,
                paymentId,
                reason.name(),
                now,
                actor,
                properties.transport().name());
    }
}
