-- #334 조회 파생 데이터. 기존 원장·정산서·지급 테이블의 값을 변경하지 않는다.
CREATE TABLE p_settlement_projection_facts (
  fact_key VARCHAR(100) PRIMARY KEY, event_id UUID NOT NULL UNIQUE,
  journal_id BIGINT NOT NULL UNIQUE REFERENCES p_ledger_transactions(id),
  payment_id BIGINT NOT NULL, seller_id BIGINT NOT NULL, schedule_id BIGINT NOT NULL,
  business_date DATE NOT NULL, kind VARCHAR(10) NOT NULL CHECK(kind IN ('SALE','REFUND')),
  amount BIGINT NOT NULL CHECK(amount>=0), meaning_hash VARCHAR(64) NOT NULL, payload TEXT NOT NULL,
  writer_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_projection_facts_xid ON p_settlement_projection_facts(writer_xid);
CREATE INDEX ix_projection_facts_seller ON p_settlement_projection_facts(seller_id,business_date,schedule_id);

CREATE TABLE p_settlement_projection_builds (
  id UUID PRIMARY KEY, mode VARCHAR(10) NOT NULL CHECK(mode IN ('B','C')),
  status VARCHAR(20) NOT NULL CHECK(status IN ('BUILDING','VERIFYING','READY','ACTIVE','RETIRED','FAILED')),
  source_snapshot TEXT, fanout_snapshot TEXT, input_count BIGINT, actor VARCHAR(100) NOT NULL,
  reason VARCHAR(500) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  verified_at TIMESTAMPTZ, last_fanout_at TIMESTAMPTZ, error VARCHAR(100)
);
CREATE UNIQUE INDEX uk_projection_building ON p_settlement_projection_builds((1))
  WHERE status IN ('BUILDING','VERIFYING','READY');
CREATE TABLE p_settlement_projection_head (
  id INT PRIMARY KEY CHECK(id=1), generation BIGINT NOT NULL DEFAULT 0,
  build_id UUID REFERENCES p_settlement_projection_builds(id), updated_at TIMESTAMPTZ
);
INSERT INTO p_settlement_projection_head(id) VALUES(1);

-- 발행 상태와 불변 source fact를 분리한다. 새 build는 같은 원천으로 재생한다.
CREATE TABLE p_settlement_projection_deliveries (
  build_id UUID NOT NULL REFERENCES p_settlement_projection_builds(id),
  fact_key VARCHAR(100) NOT NULL REFERENCES p_settlement_projection_facts(fact_key),
  lease_token UUID, leased_until TIMESTAMPTZ, published_at TIMESTAMPTZ,
  attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(80), PRIMARY KEY(build_id,fact_key)
);
CREATE INDEX ix_projection_delivery_pending ON p_settlement_projection_deliveries(build_id,fact_key)
  WHERE published_at IS NULL;

CREATE TABLE p_settlement_projection_payment_states (
  build_id UUID NOT NULL REFERENCES p_settlement_projection_builds(id), payment_id BIGINT NOT NULL,
  payload TEXT NOT NULL, pending BOOLEAN NOT NULL, revision BIGINT NOT NULL DEFAULT 1,
  meaning_hash VARCHAR(64) NOT NULL, PRIMARY KEY(build_id,payment_id)
);
CREATE TABLE p_settlement_projection_event_ids (
  build_id UUID NOT NULL REFERENCES p_settlement_projection_builds(id), event_id VARCHAR(100) NOT NULL,
  meaning_hash VARCHAR(64) NOT NULL, PRIMARY KEY(build_id,event_id)
);
CREATE TABLE p_settlement_projection_applied (
  build_id UUID NOT NULL REFERENCES p_settlement_projection_builds(id), fact_key VARCHAR(100) NOT NULL,
  meaning_hash VARCHAR(64) NOT NULL, PRIMARY KEY(build_id,fact_key)
);
CREATE TABLE p_settlement_projection_totals (
  build_id UUID NOT NULL REFERENCES p_settlement_projection_builds(id), aggregate_key VARCHAR(100) NOT NULL,
  seller_id BIGINT NOT NULL, schedule_id BIGINT NOT NULL, business_date DATE NOT NULL,
  sale_count BIGINT NOT NULL CHECK(sale_count>=0), sale_amount BIGINT NOT NULL CHECK(sale_amount>=0),
  refund_count BIGINT NOT NULL CHECK(refund_count>=0), refund_amount BIGINT NOT NULL CHECK(refund_amount>=0),
  revision BIGINT NOT NULL CHECK(revision>0), meaning_hash VARCHAR(64) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(build_id,aggregate_key)
);
CREATE INDEX ix_projection_totals_seller ON p_settlement_projection_totals(build_id,seller_id,business_date,schedule_id);
CREATE TABLE p_settlement_projection_errors (
  build_id UUID NOT NULL REFERENCES p_settlement_projection_builds(id), fact_key VARCHAR(100) NOT NULL,
  reason VARCHAR(100) NOT NULL, amount BIGINT NOT NULL, resolved BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(build_id,fact_key,reason)
);

-- 원천 payload·XID는 불변이다. 발행 상태는 별도 deliveries에서만 바꾼다.
CREATE FUNCTION p_projection_fact_immutable() RETURNS trigger LANGUAGE plpgsql AS
'BEGIN RAISE EXCEPTION ''projection source facts are immutable'' USING ERRCODE=''23514''; RETURN NULL; END';
CREATE TRIGGER projection_fact_immutable BEFORE UPDATE OR DELETE ON p_settlement_projection_facts
  FOR EACH ROW EXECUTE FUNCTION p_projection_fact_immutable();

-- SELLER 조회의 기간·원장 종류를 먼저 한정한 뒤 분개 줄을 읽는다.
CREATE INDEX ix_ledger_projection_seller_date ON p_ledger_transactions(seller_id,occurred_at,payment_id)
  WHERE source_type IN ('SALE','REFUND');

CREATE TABLE p_settlement_projection_commands (
 command_id UUID PRIMARY KEY, build_id UUID NOT NULL REFERENCES p_settlement_projection_builds(id),
 action VARCHAR(20) NOT NULL, input_hash VARCHAR(64) NOT NULL, actor VARCHAR(100) NOT NULL,
 reason VARCHAR(500) NOT NULL, result TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_projection_payment_pending ON p_settlement_projection_payment_states(build_id,payment_id) WHERE pending;
CREATE INDEX ix_projection_errors_unresolved ON p_settlement_projection_errors(build_id,fact_key) WHERE NOT resolved;
