-- #346: 최초 메시지 승인 의미와 시한만 보존한다. PG 인증값의 새 보관처를 만들지 않는다.
ALTER TABLE p_payments
  ADD COLUMN message_confirm_before TIMESTAMP,
  ADD COLUMN message_confirm_fingerprint VARCHAR(64),
  ADD CONSTRAINT ck_payment_message_confirm_pair CHECK (
    (message_confirm_before IS NULL AND message_confirm_fingerprint IS NULL)
    OR (message_confirm_before IS NOT NULL AND message_confirm_fingerprint IS NOT NULL AND message_confirm_fingerprint ~ '^[0-9a-f]{64}$'
      AND confirm_requested_at IS NOT NULL AND message_confirm_before <= expires_at + INTERVAL '5 minutes'));
CREATE INDEX ix_payment_message_ready_due ON p_payments(message_confirm_before,id)
  WHERE status='READY' AND message_confirm_before IS NOT NULL AND deleted_at IS NULL;
