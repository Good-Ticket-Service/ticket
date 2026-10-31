-- 지급 운영 명령(Task 20-10). 다시 실행해도 안전하다.
CREATE TABLE IF NOT EXISTS p_payout_operation_commands
(
    command_id   VARCHAR(80)  PRIMARY KEY,
    input_hash   VARCHAR(64)  NOT NULL,
    payout_id    BIGINT       NOT NULL,
    action       VARCHAR(10)  NOT NULL,
    actor        VARCHAR(200) NOT NULL,
    reason       VARCHAR(40)  NOT NULL,
    note         VARCHAR(200) NOT NULL,
    result       VARCHAR(20)  NOT NULL,
    explanation  VARCHAR(80)  NOT NULL,
    expected_state VARCHAR(160) NOT NULL,
    before_state VARCHAR(160) NOT NULL,
    after_state  VARCHAR(160) NOT NULL,
    processed_at TIMESTAMP    NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    created_by   VARCHAR(100) NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    updated_by   VARCHAR(100) NOT NULL,
    deleted_at   TIMESTAMP,
    deleted_by   VARCHAR(100),

    CONSTRAINT fk_p_payout_operation_commands_payout FOREIGN KEY (payout_id) REFERENCES p_payouts (id),
    CONSTRAINT ck_p_payout_operation_commands_action CHECK (action IN ('RETRY')),
    CONSTRAINT ck_p_payout_operation_commands_result CHECK (result IN ('APPLIED', 'DENIED', 'STALE')),
    CONSTRAINT ck_p_payout_operation_commands_not_deleted CHECK (deleted_at IS NULL)
);
CREATE INDEX IF NOT EXISTS ix_p_payout_operation_commands_history
    ON p_payout_operation_commands (payout_id, processed_at DESC, command_id DESC);
