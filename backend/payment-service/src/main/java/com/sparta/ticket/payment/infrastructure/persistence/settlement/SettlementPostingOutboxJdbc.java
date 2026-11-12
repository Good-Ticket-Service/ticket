package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 발행 임대와 금융 처리 상태를 분리한다. broker 호출 중에는 이 트랜잭션을 유지하지 않는다. */
@Repository
@RequiredArgsConstructor
public class SettlementPostingOutboxJdbc {
    private final JdbcTemplate jdbc;

    public record Delivery(long jobId, UUID eventId, long paymentId, String payload, UUID token, int attempts) {}

    /** broker 보존 밖 유실도 금융 PENDING 책임으로 복구한다. 격리 인수분·처리 중 행은 건드리지 않는다. */
    @Transactional
    public int redriveOverdue(int limit, Duration age) {
        if (limit < 1 || limit > 1000 || age == null || age.compareTo(Duration.ofMinutes(1)) < 0)
            throw new IllegalArgumentException("재전달 묶음/노후 기준이 잘못됐다");
        return jdbc.update("""
                WITH candidates AS (
                  SELECT j.id FROM p_settlement_jobs j WHERE j.transport='KAFKA' AND j.status='PENDING'
                    AND j.deleted_at IS NULL AND j.created_at<(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-(?*interval '1 millisecond')
                    AND j.published_at<(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-(?*interval '1 millisecond')
                    AND NOT EXISTS(SELECT 1 FROM p_financial_consumer_incidents i WHERE i.source_job_id=j.id AND i.status<>'RESOLVED')
                  ORDER BY j.created_at,j.id LIMIT ? FOR UPDATE OF j SKIP LOCKED
                )
                UPDATE p_settlement_jobs j SET published_at=NULL,publish_lease_token=NULL,publish_lease_until=NULL,
                  publish_next_attempt_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),publish_last_error='FINANCIAL_OVERDUE_REDRIVE',
                  updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_by='SYSTEM'
                FROM candidates c WHERE j.id=c.id
                """, age.toMillis(), age.toMillis(), limit);
    }

    @Transactional
    public List<Delivery> claim(int limit, Duration lease) {
        if (limit < 1 || limit > 1000 || lease == null || lease.isNegative() || lease.isZero())
            throw new IllegalArgumentException("유효한 발행 묶음/임대가 필요하다");
        UUID token = UUID.randomUUID();
        return jdbc.query(
                """
                WITH candidates AS (
                  SELECT id FROM p_settlement_jobs WHERE transport='KAFKA' AND status='PENDING' AND deleted_at IS NULL
                    AND published_at IS NULL AND publish_next_attempt_at<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
                    AND (publish_lease_until IS NULL OR publish_lease_until<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'))
                  ORDER BY publish_next_attempt_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE p_settlement_jobs j SET publish_lease_token=?,
                  publish_lease_until=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')+(?*interval '1 millisecond'),
                  publish_attempts=publish_attempts+1,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_by='SYSTEM'
                FROM candidates c WHERE j.id=c.id
                RETURNING j.id,j.event_id,j.payment_id,j.event_payload,j.publish_attempts
                """,
                (rs, row) -> new Delivery(
                        rs.getLong(1),
                        rs.getObject(2, UUID.class),
                        rs.getLong(3),
                        rs.getString(4),
                        token,
                        rs.getInt(5)),
                limit,
                token,
                lease.toMillis());
    }

    @Transactional
    public int published(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) return 0;
        int[] counts = jdbc.batchUpdate(
                """
                UPDATE p_settlement_jobs SET published_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),publish_lease_token=NULL,
                  publish_lease_until=NULL,publish_last_error=NULL,updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_by='SYSTEM'
                WHERE id=? AND event_id=? AND publish_lease_token=? AND published_at IS NULL
                """,
                deliveries.stream()
                        .map(item -> new Object[] {item.jobId(), item.eventId(), item.token()})
                        .toList());
        return (int) java.util.Arrays.stream(counts)
                .filter(count -> count > 0 || count == java.sql.Statement.SUCCESS_NO_INFO)
                .count();
    }

    @Transactional
    public void failed(List<Delivery> deliveries, String reason, Duration baseDelay) {
        if (deliveries.isEmpty()) return;
        if (!List.of("DELIVERY_UNCERTAIN", "RUN_BUDGET", "INTERRUPTED").contains(reason)
                || baseDelay == null
                || baseDelay.isNegative()
                || baseDelay.isZero()) throw new IllegalArgumentException("고정 오류와 양수 재시도 간격이 필요하다");
        jdbc.batchUpdate(
                """
                UPDATE p_settlement_jobs SET publish_lease_token=NULL,publish_lease_until=NULL,
                  publish_last_error=?,publish_next_attempt_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')+(?*interval '1 millisecond'),
                  updated_at=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),updated_by='SYSTEM'
                WHERE id=? AND event_id=? AND publish_lease_token=? AND published_at IS NULL
                """,
                deliveries.stream()
                        .map(item -> new Object[] {
                            reason,
                            Math.min(30000, baseDelay.toMillis() * (1L << Math.min(item.attempts() - 1, 5))),
                            item.jobId(),
                            item.eventId(),
                            item.token()
                        })
                        .toList());
    }

    public long unpublished() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_jobs WHERE transport='KAFKA' AND status='PENDING' AND published_at IS NULL",
                Long.class);
    }
}
