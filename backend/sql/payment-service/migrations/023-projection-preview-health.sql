
-- #344: 집계 실행부를 중지하고 단일 TX에서 적용한 뒤 새 버전만 기동한다.
-- 금융 원본과 applied receipt는 보존하고 조회용 미처리 상태만 복원한다.
ALTER TABLE p_settlement_projection_deliveries
  ADD COLUMN seller_id BIGINT,
  ADD COLUMN schedule_id BIGINT,
  ADD COLUMN business_date DATE,
  ADD COLUMN applied BOOLEAN NOT NULL DEFAULT false;

UPDATE p_settlement_projection_deliveries d
SET seller_id=f.seller_id,schedule_id=f.schedule_id,business_date=f.business_date,
  applied=EXISTS(SELECT 1 FROM p_settlement_projection_applied a
    WHERE a.build_id=d.build_id AND a.fact_key=d.fact_key AND a.meaning_hash=f.meaning_hash)
FROM p_settlement_projection_facts f WHERE f.fact_key=d.fact_key;

ALTER TABLE p_settlement_projection_deliveries
  ALTER COLUMN seller_id SET NOT NULL,
  ALTER COLUMN schedule_id SET NOT NULL,
  ALTER COLUMN business_date SET NOT NULL;

CREATE INDEX ix_projection_delivery_seller_unpublished
  ON p_settlement_projection_deliveries(build_id,seller_id,business_date,schedule_id)
  WHERE published_at IS NULL;
CREATE INDEX ix_projection_delivery_seller_unapplied
  ON p_settlement_projection_deliveries(build_id,seller_id,business_date,schedule_id)
  WHERE NOT applied;
