-- 최초 승인 결과는 금융 상태 전이와 함께 고정한다. 송신은 별도 lease/TX 경계로 수행한다(#346).
CREATE TABLE p_payment_result_outbox (
    event_id UUID PRIMARY KEY,
    payment_id BIGINT NOT NULL UNIQUE,
    order_id VARCHAR(64) NOT NULL UNIQUE,
    reservation_id BIGINT NOT NULL,
    result_status VARCHAR(10) NOT NULL CHECK (result_status IN ('DONE','FAILED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    meaning_hash VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts>=0),
    last_error VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL DEFAULT 'SYSTEM',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(100) NOT NULL DEFAULT 'SYSTEM',
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX ix_payment_result_outbox_pending ON p_payment_result_outbox(next_attempt_at,event_id)
    WHERE published_at IS NULL;
