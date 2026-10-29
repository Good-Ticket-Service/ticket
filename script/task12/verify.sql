-- Task 12 정합성 대조(#141). payment DB와 fake-pg 거래 기록을 orderId·PG 거래 키로 건별 비교한다.
-- 단독 환경의 관리자 계정으로 두 스키마를 함께 읽는다. 읽기만 한다.
-- 결과: 한 줄에 위반 하나. 열은 check, order_id, detail. 위반이 없으면 빈 결과다.
-- psql 변수 provider로 대상 PG를 고른다(TOSS·KAKAO·ALL). TOSS·KAKAO 시나리오를 동시에 돌리기 때문이다.
-- 판정 기준의 원본은 결제 담당 Task 12 계획의 V1~V10이다. V8(다른 시도의 일시적 복수 과금)과 V9(비밀값)는 실행기가 따로 기록한다.

WITH pay AS (
    SELECT p.id, p.order_id, p.pg_provider, p.pg_transaction_id, p.amount, p.status
    FROM payment_schema.p_payments p
    WHERE p.deleted_at IS NULL AND (:'provider' = 'ALL' OR p.pg_provider = :'provider')
),
pg AS (
    SELECT t.provider, t.order_id, t.payment_key, t.amount, t.status,
           COALESCE(t.canceled_amount, CASE WHEN t.status = 'CANCELED' THEN t.amount ELSE 0 END) AS canceled_amount
    FROM fake_pg_schema.pg_transaction t
    WHERE :'provider' = 'ALL' OR t.provider = :'provider'
),
ref AS (
    SELECT r.payment_id, count(*) AS rows, max(r.status) AS status, max(r.refund_amount) AS refund_amount,
           max(r.recovery_mode) AS recovery_mode, max(r.reason) AS reason
    FROM payment_schema.p_refunds r
    WHERE r.deleted_at IS NULL
    GROUP BY r.payment_id
),
joined AS (
    SELECT pay.*, pg.status AS pg_status, pg.amount AS pg_amount, pg.payment_key, pg.canceled_amount,
           ref.rows AS refund_rows, ref.status AS refund_status, ref.refund_amount, ref.recovery_mode, ref.reason
    FROM pay
    LEFT JOIN pg ON pg.provider = pay.pg_provider AND pg.order_id = pay.order_id
    LEFT JOIN ref ON ref.payment_id = pay.id
),
violations AS (
    -- V1: 같은 orderId의 PG 거래(=과금 가능 기록)는 하나여야 한다
    SELECT 'V1_DUPLICATE_PG_TRANSACTION' AS check_id, order_id, count(*)::text AS detail
    FROM pg GROUP BY provider, order_id HAVING count(*) > 1
    UNION ALL
    -- V2: 결제당 환불 행은 하나 이하
    SELECT 'V2_DUPLICATE_REFUND_ROW', order_id, refund_rows::text FROM joined WHERE refund_rows > 1
    UNION ALL
    -- V3: payment 상태와 PG 상태가 맞아야 한다
    SELECT 'V3_STATUS_MISMATCH', order_id, status || ' vs PG ' || COALESCE(pg_status, '없음')
    FROM joined
    WHERE (status = 'DONE' AND pg_status IS DISTINCT FROM 'DONE')
       OR (status = 'CANCELED' AND pg_status IS DISTINCT FROM 'CANCELED')
       OR (status = 'READY' AND pg_status IN ('DONE', 'CANCELED', 'PARTIAL_CANCELED'))
    UNION ALL
    -- V4: FAILED인데 PG에 돈이 빠진 채(DONE·부분 취소) 남은 건. 늦은 승인은 AUTO_VOID 환불로 CANCELED가 돼야 한다
    SELECT 'V4_FAILED_BUT_CHARGED', order_id,
           'PG ' || pg_status || ', 환불 ' || COALESCE(refund_status, '없음') || '/' || COALESCE(recovery_mode, '-')
    FROM joined
    WHERE status = 'FAILED' AND pg_status IN ('DONE', 'PARTIAL_CANCELED')
    UNION ALL
    -- V5: 관찰 기간 뒤에도 처리 중으로 남은 건. 운영 인수 상태(VERIFY_ONLY·PAUSED)는 실행기가 기대 결과와 비교한다
    SELECT 'V5_UNRESOLVED', order_id, 'payment ' || status || ', 환불 ' || COALESCE(refund_status, '없음') || '/' || COALESCE(recovery_mode, '-')
    FROM joined
    WHERE status = 'IN_PROGRESS'
       OR (refund_status IN ('WAITING', 'REQUESTED') AND recovery_mode IN ('ACTIVE', 'SLOW'))
    UNION ALL
    -- V6: 금액. PG 승인액 = 결제 금액, 환불액 = 결제 금액
    SELECT 'V6_AMOUNT_MISMATCH', order_id, 'payment ' || amount || ', PG ' || pg_amount || ', 환불 ' || COALESCE(refund_amount::text, '-')
    FROM joined
    WHERE (pg_status IN ('DONE', 'CANCELED', 'PARTIAL_CANCELED') AND pg_amount <> amount)
       OR (refund_amount IS NOT NULL AND refund_amount <> amount)
    UNION ALL
    -- V10: 결제에 저장한 PG 거래 키와 실제 PG 거래 키가 같아야 한다(다른 거래 키로 처리하지 않음)
    SELECT 'V10_TRANSACTION_KEY_MISMATCH', order_id, pg_transaction_id || ' vs ' || payment_key
    FROM joined
    WHERE status IN ('DONE', 'CANCELED') AND pg_transaction_id IS DISTINCT FROM payment_key
    UNION ALL
    -- PG에는 있는데 payment에 없는 과금(내부 미등록 거래)
    SELECT 'V3_PG_ONLY_CHARGE', pg.order_id, pg.status
    FROM pg LEFT JOIN pay ON pay.pg_provider = pg.provider AND pay.order_id = pg.order_id
    WHERE pay.id IS NULL AND pg.status IN ('DONE', 'PARTIAL_CANCELED')
)
SELECT check_id, order_id, detail FROM violations
UNION ALL
-- V7: PG 순수취액과 payment DONE 금액 합. 다르면 한 줄을 낸다
SELECT 'V7_NET_MISMATCH', '-', 'PG 순수취 ' || pg_net || ', payment DONE 합 ' || done_sum
FROM (
    SELECT (SELECT COALESCE(sum(amount - canceled_amount), 0) FROM pg WHERE status IN ('DONE', 'CANCELED', 'PARTIAL_CANCELED')) AS pg_net,
           (SELECT COALESCE(sum(amount), 0) FROM pay WHERE status = 'DONE') AS done_sum
) n
WHERE pg_net <> done_sum
ORDER BY 1, 2;
