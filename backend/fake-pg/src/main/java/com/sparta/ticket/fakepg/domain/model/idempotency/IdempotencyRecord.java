package com.sparta.ticket.fakepg.domain.model.idempotency;

import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 멱등 키 하나의 처리 기록. 처음 받으면 PROCESSING으로 넣고, 업무 응답이 정해지면 COMPLETED로 응답을 저장한다.
 * 같은 키가 다시 오면 COMPLETED는 저장한 응답을 그대로 돌려주고, PROCESSING은 409로 막는다.
 */
@Getter
@Entity
@Table(name = "pg_idempotency_record")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgProvider provider;

    @Column(nullable = false, length = 300)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgOperation operation;

    /** 요청 내용의 해시. 같은 키로 다른 내용을 보내면 422로 막는다. */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyState state;

    private Integer statusCode;

    @Column(columnDefinition = "text")
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static IdempotencyRecord processing(
            PgProvider provider, String idempotencyKey, PgOperation operation, String fingerprint, LocalDateTime now) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.provider = provider;
        record.idempotencyKey = idempotencyKey;
        record.operation = operation;
        record.fingerprint = fingerprint;
        record.state = IdempotencyState.PROCESSING;
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    public boolean isSameRequest(PgOperation operation, String fingerprint) {
        return this.operation == operation && this.fingerprint.equals(fingerprint);
    }

    public void complete(int statusCode, String responseBody, LocalDateTime now) {
        this.state = IdempotencyState.COMPLETED;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.updatedAt = now;
    }

    public boolean isCompleted() {
        return state == IdempotencyState.COMPLETED;
    }
}
