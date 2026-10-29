package com.sparta.ticket.payment.infrastructure.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentObservationIntegrationTest extends PaymentIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PaymentObservationTransactions reader;

    @Autowired
    private PaymentObservation observation;

    @Autowired
    private MeterRegistry meters;

    @Test
    void pendingIncludesRestrictedButNotDoneAndFutureSlowIsNotDue() {
        tx.executeWithoutResult(ignored -> {
            double dueBefore = sample("payment.db.jobs.due", "task", "refund");
            double pausedBefore = sample("payment.db.refunds.pending", "TOSS", "FAILED", "PAUSED", "AUTO_VOID");
            long id = jdbc.queryForObject("""
                    INSERT INTO p_payments(order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,
                    pg_provider,expires_at,total_price,discount_amount,amount,status,created_at,updated_at,created_by,updated_by)
                    VALUES (?,nextval('p_payments_id_seq'),1,2,3,4,'TOSS',now(),1000,0,1000,'FAILED',now(),now(),'SYSTEM','SYSTEM') RETURNING id
                    """, Long.class, "observe-" + UUID.randomUUID());
            var past =
                    Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(20));
            jdbc.update(
                    """
                    INSERT INTO p_refunds(payment_id,reservation_id,refund_amount,reason,status,recovery_mode,
                    next_attempt_at,created_at,updated_at,created_by,updated_by)
                    VALUES (?,1,1000,'AUTO_VOID','REQUESTED','SLOW',?, ?,?,'SYSTEM','SYSTEM')
                    """,
                    id,
                    Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(10)),
                    past,
                    past);
            var samples = reader.read();
            assertThat(sample("payment.db.jobs.due", "task", "refund")).isEqualTo(dueBefore);
            assertThat(samples).anySatisfy(s -> {
                assertThat(s.name()).isEqualTo("payment.db.refunds.oldest.record.age.seconds");
                assertThat(s.tags()).contains("SLOW", "AUTO_VOID");
                assertThat(s.value()).isGreaterThanOrEqualTo(1190);
            });
            observation.refresh();
            jdbc.update(
                    "UPDATE p_refunds SET status='FAILED',recovery_mode='PAUSED',recovery_blocked=true WHERE payment_id=?",
                    id);
            assertThat(sample("payment.db.refunds.pending", "TOSS", "FAILED", "PAUSED", "AUTO_VOID"))
                    .isEqualTo(pausedBefore + 1);
            assertThat(sample("payment.db.refunds.restriction.time.unknown", "TOSS", "FAILED", "PAUSED", "AUTO_VOID"))
                    .isPositive();
            jdbc.update("UPDATE p_refunds SET status='DONE' WHERE payment_id=?", id);
            assertThat(sample("payment.db.refunds.pending", "TOSS", "FAILED", "PAUSED", "AUTO_VOID"))
                    .isEqualTo(pausedBefore);
            ignored.setRollbackOnly();
        });
        observation.refresh();
        assertThat(meters.get("payment.observation.success").gauge().value()).isEqualTo(1);
    }

    private double sample(String name, String... requiredTags) {
        return reader.read().stream()
                .filter(s -> s.name().equals(name) && s.tags().containsAll(java.util.List.of(requiredTags)))
                .mapToDouble(PaymentObservationTransactions.Sample::value)
                .sum();
    }
}
