package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class FinancialReplayOutboxJdbc {
    private final JdbcTemplate jdbc;

    public record Delivery(UUID commandId, String topic, String key, String payload, UUID token, int attempts) {}

    @Transactional
    public int redriveOverdue() {
        return jdbc.update("""
                WITH candidates AS (
                  SELECT command_id FROM p_financial_replay_commands WHERE status='PUBLISHED'
                    AND published_at<(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-interval '10 minutes'
                  ORDER BY published_at,command_id LIMIT 20 FOR UPDATE SKIP LOCKED
                )
                UPDATE p_financial_replay_commands c SET status='REQUESTED',explanation='PROCESSING_OVERDUE_REDRIVE',
                  next_attempt_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),last_error='PROCESSING_OVERDUE_REDRIVE',
                  updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') FROM candidates s WHERE s.command_id=c.command_id
                """);
    }

    @Transactional
    public List<Delivery> claim(Duration lease) {
        UUID token = UUID.randomUUID();
        return jdbc.query(
                """
                WITH candidates AS (
                  SELECT command_id FROM p_financial_replay_commands WHERE status='REQUESTED'
                    AND next_attempt_at<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                    AND (lease_until IS NULL OR lease_until<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'))
                  ORDER BY next_attempt_at,command_id LIMIT 20 FOR UPDATE SKIP LOCKED
                )
                UPDATE p_financial_replay_commands c SET lease_token=?,
                  lease_until=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')+(?*interval '1 millisecond'),
                  attempts=attempts+1,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                FROM candidates s WHERE s.command_id=c.command_id RETURNING c.command_id,c.target_topic,c.message_key,c.payload,c.attempts
                """,
                (rs, row) -> new Delivery(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        token,
                        rs.getInt(5)),
                token,
                lease.toMillis());
    }

    @Transactional
    public void published(Delivery item) {
        jdbc.update("""
                UPDATE p_financial_replay_commands SET status='PUBLISHED',explanation='BROKER_ACKNOWLEDGED',
                  published_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),lease_token=NULL,lease_until=NULL,last_error=NULL,
                  updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                WHERE command_id=? AND lease_token=? AND status='REQUESTED'
                """, item.commandId(), item.token());
    }

    @Transactional
    public void failed(Delivery item, Duration delay) {
        jdbc.update(
                """
                UPDATE p_financial_replay_commands SET lease_token=NULL,lease_until=NULL,last_error='DELIVERY_UNCERTAIN',
                  next_attempt_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')+(?*interval '1 millisecond'),
                  updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                WHERE command_id=? AND lease_token=? AND status='REQUESTED'
                """,
                Math.min(30000, delay.toMillis() * (1L << Math.min(item.attempts() - 1, 5))),
                item.commandId(),
                item.token());
    }
}
