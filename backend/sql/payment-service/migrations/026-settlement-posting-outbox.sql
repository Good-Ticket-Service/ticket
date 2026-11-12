-- #363: 금융 작업을 영속 Outbox로 확장한다. 기존 작업은 DB가 계속 인수한다.
BEGIN;
ALTER TABLE p_settlement_jobs
  ADD COLUMN transport VARCHAR(10) NOT NULL DEFAULT 'DB',
  ADD COLUMN event_id UUID NOT NULL DEFAULT gen_random_uuid(),
  ADD COLUMN event_payload TEXT,
  ADD COLUMN published_at TIMESTAMP,
  ADD COLUMN publish_lease_token UUID,
  ADD COLUMN publish_lease_until TIMESTAMP,
  ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN publish_next_attempt_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),
  ADD COLUMN publish_last_error VARCHAR(80),
  ADD COLUMN processing_result VARCHAR(24);

UPDATE p_settlement_jobs SET event_payload=jsonb_build_object(
  'version',1,'eventId',event_id::text,'jobId',id,'paymentId',payment_id,
  'reason',reason,'occurredAt',created_at)::text,
  processing_result=CASE WHEN status='DONE' THEN 'LEGACY' END;

ALTER TABLE p_settlement_jobs
  ADD CONSTRAINT uk_settlement_job_event UNIQUE(event_id),
  ADD CONSTRAINT ck_settlement_job_transport CHECK(transport IN ('DB','KAFKA')),
  ADD CONSTRAINT ck_settlement_job_publish_attempts CHECK(publish_attempts>=0),
  ADD CONSTRAINT ck_settlement_job_publish_lease CHECK((publish_lease_token IS NULL)=(publish_lease_until IS NULL)),
  ADD CONSTRAINT ck_settlement_job_published CHECK(published_at IS NULL OR transport='KAFKA'),
  ADD CONSTRAINT ck_settlement_job_result CHECK(processing_result IN ('APPLIED','UNPOSTED','NO_FINANCIAL_EFFECT','LEGACY')),
  ADD CONSTRAINT ck_settlement_job_payload CHECK(
    (event_payload IS NULL AND transport='DB') OR ((
      event_payload::jsonb->>'version'='1'
      AND event_payload::jsonb->>'eventId'=event_id::text
      AND event_payload::jsonb->>'jobId'=id::text
      AND event_payload::jsonb->>'paymentId'=payment_id::text
      AND event_payload::jsonb->>'reason'=reason
      AND event_payload::jsonb->>'occurredAt' IS NOT NULL) IS TRUE));

-- 구버전 DB 워커가 읽는 기존 due 인덱스는 전환 중에도 유지한다.
CREATE INDEX ix_settlement_job_db_due ON p_settlement_jobs(next_attempt_at,id)
  WHERE status='PENDING' AND transport='DB';
CREATE INDEX ix_settlement_job_publish_due ON p_settlement_jobs(publish_next_attempt_at,id)
  WHERE status='PENDING' AND transport='KAFKA' AND published_at IS NULL;

CREATE FUNCTION guard_settlement_job_identity() RETURNS trigger LANGUAGE plpgsql AS '
BEGIN
  IF (OLD.id,OLD.payment_id,OLD.reason,OLD.transport,OLD.event_id,OLD.event_payload)
    IS DISTINCT FROM (NEW.id,NEW.payment_id,NEW.reason,NEW.transport,NEW.event_id,NEW.event_payload) THEN
    RAISE EXCEPTION ''settlement job identity is immutable'' USING ERRCODE=''23514'';
  END IF;
  RETURN NEW;
END';
CREATE TRIGGER settlement_job_identity BEFORE UPDATE ON p_settlement_jobs
  FOR EACH ROW EXECUTE FUNCTION guard_settlement_job_identity();
COMMIT;
