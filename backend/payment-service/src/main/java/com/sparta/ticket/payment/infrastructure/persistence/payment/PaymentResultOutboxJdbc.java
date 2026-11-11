package com.sparta.ticket.payment.infrastructure.persistence.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.payment.PaymentResultPublisher;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.infrastructure.messaging.PaymentResultMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 금융 상태와 최초 결과를 같은 TX에 고정한다. 발행 완료가 되어도 초기 결과 식별을 유지한다. */
@Repository
@RequiredArgsConstructor
public class PaymentResultOutboxJdbc implements PaymentResultPublisher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public record Delivery(UUID eventId, String key, String payload, UUID leaseToken, int attempts) {}

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentFinalized(Payment payment) {
        if (payment.getStatus() != PaymentStatus.DONE && payment.getStatus() != PaymentStatus.FAILED) return;
        var message = PaymentResultMessage.from(payment, OffsetDateTime.now(clock));
        String meaningHash = hash(message.meaning());
        int inserted = jdbc.update(
                """
                INSERT INTO p_payment_result_outbox(event_id,payment_id,order_id,reservation_id,result_status,
                  occurred_at,meaning_hash,payload)
                VALUES(?::uuid,?,?,?,?,?,?,?) ON CONFLICT(payment_id) DO NOTHING
                """,
                message.eventId(),
                message.paymentId(),
                message.orderId(),
                message.reservationId(),
                message.status(),
                message.occurredAt(),
                meaningHash,
                json(message));
        if (inserted == 0
                && !meaningHash.equals(jdbc.queryForObject(
                        "SELECT meaning_hash FROM p_payment_result_outbox WHERE payment_id=?",
                        String.class,
                        payment.getId()))) {
            throw new IllegalStateException("최초 결제 결과의 의미가 충돌한다");
        }
    }

    /** 환불 접수/완료는 payment.result가 아니다. #319 환불 전환 때 별도 포트와 사건으로 연결한다. */
    @Override
    public void refundFinalized(Payment payment, Refund refund) {}

    @Transactional
    public List<Delivery> claim(int limit, Duration lease) {
        UUID token = UUID.randomUUID();
        return jdbc.query(
                """
                WITH candidates AS (
                  SELECT event_id FROM p_payment_result_outbox
                  WHERE published_at IS NULL AND next_attempt_at<=CURRENT_TIMESTAMP
                    AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                  ORDER BY next_attempt_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE p_payment_result_outbox o SET lease_token=?,
                  lease_until=CURRENT_TIMESTAMP+(?*interval '1 millisecond'),attempts=o.attempts+1,
                  updated_at=CURRENT_TIMESTAMP,updated_by='SYSTEM'
                FROM candidates c WHERE o.event_id=c.event_id
                RETURNING o.event_id,o.reservation_id::text,o.payload,o.attempts
                """,
                (rs, row) -> new Delivery(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), token, rs.getInt(4)),
                limit,
                token,
                lease.toMillis());
    }

    @Transactional
    public boolean published(Delivery delivery) {
        return jdbc.update("""
                UPDATE p_payment_result_outbox SET published_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,
                  last_error=NULL,updated_at=CURRENT_TIMESTAMP,updated_by='SYSTEM'
                WHERE event_id=? AND lease_token=? AND published_at IS NULL
                """, delivery.eventId(), delivery.leaseToken()) == 1;
    }

    @Transactional
    public void failed(Delivery delivery, String reason, Duration delay) {
        jdbc.update("""
                UPDATE p_payment_result_outbox SET lease_token=NULL,lease_until=NULL,last_error=?,
                  next_attempt_at=CURRENT_TIMESTAMP+(?*interval '1 millisecond'),
                  updated_at=CURRENT_TIMESTAMP,updated_by='SYSTEM'
                WHERE event_id=? AND lease_token=? AND published_at IS NULL
                """, reason, delay.toMillis(), delivery.eventId(), delivery.leaseToken());
    }

    public long pending() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_payment_result_outbox WHERE published_at IS NULL", Long.class);
    }

    public double oldestSeconds() {
        return jdbc.queryForObject("""
                SELECT coalesce(greatest(extract(epoch FROM CURRENT_TIMESTAMP-min(occurred_at)),0),0)
                FROM p_payment_result_outbox WHERE published_at IS NULL
                """, Double.class);
    }

    private String json(PaymentResultMessage message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("결제 결과 직렬화 실패");
        }
    }

    private static String hash(String meaning) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(meaning.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
