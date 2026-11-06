package com.sparta.ticket.payment.infrastructure.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementItemRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementDbFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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

    @Autowired
    private SettlementRules rules;

    @Autowired
    private SettlementItemRepository items;

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

    @Test
    @DisplayName("결제 수 지표는 미확정(READY·IN_PROGRESS)만 세고 끝난 결제는 세지 않는다")
    void paymentCountsOnlyUnfinished() {
        tx.executeWithoutResult(ignored -> {
            double readyBefore = sample("payment.db.payments.unfinished", "KAKAO", "READY");
            double inProgressBefore = sample("payment.db.payments.unfinished", "KAKAO", "IN_PROGRESS");
            for (String status : List.of("READY", "IN_PROGRESS", "DONE", "FAILED", "CANCELED")) {
                jdbc.update("""
                        INSERT INTO p_payments(order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,
                        pg_provider,expires_at,total_price,discount_amount,amount,status,created_at,updated_at,created_by,updated_by)
                        VALUES (?,nextval('p_payments_id_seq'),1,2,3,4,'KAKAO',now(),1000,0,1000,?,now(),now(),'SYSTEM','SYSTEM')
                        """, "observe-" + UUID.randomUUID(), status);
            }

            var samples = reader.read();

            assertThat(sample("payment.db.payments.unfinished", "KAKAO", "READY"))
                    .isEqualTo(readyBefore + 1);
            assertThat(sample("payment.db.payments.unfinished", "KAKAO", "IN_PROGRESS"))
                    .isEqualTo(inProgressBefore + 1);
            assertThat(samples)
                    .filteredOn(s -> s.name().startsWith("payment.db.payments"))
                    .allSatisfy(s -> {
                        assertThat(s.name()).isEqualTo("payment.db.payments.unfinished");
                        assertThat(s.tags()).containsAnyOf("READY", "IN_PROGRESS");
                    });
            ignored.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("정산서 발행 대기는 정산 가능일 4시간이 지난 PENDING SALE만 센다")
    void statementDueCountsOnlyPastEligibleFourHours() {
        tx.executeWithoutResult(ignored -> {
            double before = sample("settlement.db.jobs.due", "task", "statement");
            for (int hoursAgo : List.of(5, 3)) {
                long paymentId = SettlementDbFixture.payment(jdbc, 990_001L, 10_000);
                long itemId = items.saveAndFlush(SettlementDbFixture.sale(rules, paymentId, 990_001L, 10_000))
                        .getId();
                jdbc.update(
                        "UPDATE p_settlement_items SET eligible_at = localtimestamp - make_interval(hours => ?)"
                                + " WHERE id = ? AND kind = 'SALE' AND status = 'PENDING'",
                        hoursAgo,
                        itemId);
            }

            // Note: 4시간 지난 항목(5시간 전)만 대기로 센다. 3시간 전 항목은 아직 발행 시각 전이다.
            assertThat(sample("settlement.db.jobs.due", "task", "statement")).isEqualTo(before + 1);
            ignored.setRollbackOnly();
        });
    }

    private double sample(String name, String... requiredTags) {
        return reader.read().stream()
                .filter(s -> s.name().equals(name) && s.tags().containsAll(java.util.List.of(requiredTags)))
                .mapToDouble(PaymentObservationTransactions.Sample::value)
                .sum();
    }
}
