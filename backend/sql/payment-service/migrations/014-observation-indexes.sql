-- DB 관측(15초 주기)이 끝난 거래까지 전체 스캔하지 않게 미완료·위험 상태 부분 인덱스를 둔다. 다시 실행해도 안전하다.
-- Note: 운영 DB에 거래가 많으면 잠금을 피하려고 각 문장을 CREATE INDEX CONCURRENTLY로 따로 실행한다(트랜잭션 밖).
CREATE INDEX IF NOT EXISTS ix_p_refunds_unfinished
    ON p_refunds (status)
    WHERE status IN ('WAITING', 'REQUESTED', 'FAILED');
CREATE INDEX IF NOT EXISTS ix_p_settlement_items_unposted_sale ON p_settlement_items (id)
    WHERE kind = 'SALE' AND status = 'UNPOSTED';
CREATE INDEX IF NOT EXISTS ix_p_settlement_items_held_sale ON p_settlement_items (hold_reason, updated_at)
    WHERE kind = 'SALE' AND status = 'HELD';
