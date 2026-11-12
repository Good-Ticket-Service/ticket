-- 검증용 합성 이력. 실제 PG 승인 부하가 아니며 원장 업무 키·차대·판매자 금액을 독립 검증한다.
INSERT INTO p_payments(id,order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,pg_provider,
  pg_transaction_id,expires_at,schedule_start_at,total_price,discount_amount,amount,status,approved_at,
  created_at,created_by,updated_at,updated_by)
SELECT g,'sk363-history-'||g,g,1,1+(g-1)%1000,1,1+(g-1)%10000,'TOSS','sk363-history-key-'||g,
  timestamp '2026-10-19 11:00',timestamp '2026-11-07 19:00',10000,0,10000,
  CASE WHEN g%10=0 THEN 'CANCELED' ELSE 'DONE' END,timestamp '2026-10-19 10:00',
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture'
FROM generate_series(1,${history}) g;

INSERT INTO p_refunds(id,payment_id,reservation_id,refund_amount,reason,status,pg_cancel_id,requested_at,refunded_at,
  created_at,created_by,updated_at,updated_by)
SELECT id,id,id,10000,'USER_CANCEL','DONE','sk363-cancel-'||id,
  approved_at+interval '1 hour',approved_at+interval '1 hour',
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture'
FROM p_payments WHERE id%10=0;

INSERT INTO p_settlement_items(id,kind,status,seller_id,payment_id,refund_id,performance_id,schedule_id,approved_at,
  gross_amount,discount_amount,booking_fee,paid_amount,commission,commission_vat,seller_amount,
  booking_fee_net,booking_fee_vat,policy_version,calendar_version,eligible_at,
  created_at,created_by,updated_at,updated_by)
SELECT id,'SALE',CASE WHEN id%10=0 THEN 'CANCELED' ELSE 'PENDING' END,seller_id,id,
  CASE WHEN id%10=0 THEN id END,performance_id,schedule_id,approved_at,
  10000,0,0,10000,1200,120,8680,0,0,'2026-10-30-v1','kr-2026-2027-v1',timestamp '2026-11-12 04:00',
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture' FROM p_payments;

INSERT INTO p_ledger_transactions(source_type,source_id,action,seller_id,payment_id,policy_version,occurred_at,
  created_at,created_by,updated_at,updated_by)
SELECT 'SALE',id,'POST',seller_id,id,'2026-10-30-v1',approved_at,
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture' FROM p_payments;

INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
SELECT t.id,v.line_no,v.account,v.debit,v.credit,LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture'
FROM p_ledger_transactions t
CROSS JOIN (VALUES(1,'PG_CLEARING',10000,0),(2,'SELLER_PAYABLE',0,8680),
  (3,'COMMISSION_REVENUE',0,1200),(4,'VAT_PAYABLE',0,120)) v(line_no,account,debit,credit);

INSERT INTO p_ledger_transactions(source_type,source_id,action,seller_id,payment_id,refund_id,policy_version,
  reverses_transaction_id,occurred_at,created_at,created_by,updated_at,updated_by)
SELECT 'REFUND',r.id,'REVERSE',p.seller_id,p.id,r.id,'2026-10-30-v1',t.id,r.refunded_at,
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture'
FROM p_refunds r JOIN p_payments p ON p.id=r.payment_id
JOIN p_ledger_transactions t ON t.source_type='SALE' AND t.source_id=p.id AND t.action='POST';

INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
SELECT t.id,e.line_no,e.account,e.credit,e.debit,LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture'
FROM p_ledger_transactions t JOIN p_ledger_entries e ON e.transaction_id=t.reverses_transaction_id
WHERE t.source_type='REFUND';

INSERT INTO p_settlement_jobs(payment_id,reason,status,attempts,next_attempt_at,done_at,
  created_at,created_by,updated_at,updated_by)
SELECT id,'PAYMENT_APPROVED','DONE',0,approved_at,approved_at,
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture' FROM p_payments;
INSERT INTO p_settlement_jobs(payment_id,reason,status,attempts,next_attempt_at,done_at,
  created_at,created_by,updated_at,updated_by)
SELECT payment_id,'REFUND_COMPLETED','DONE',0,refunded_at,refunded_at,
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture' FROM p_refunds;

-- 신규 작업의 READY 상태만 미리 만든다. 승인 결과 확정·job/Outbox 저장은 측정 중 실제 TX로 수행한다.
INSERT INTO p_payments(id,order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,pg_provider,
  expires_at,schedule_start_at,total_price,discount_amount,amount,status,
  created_at,created_by,updated_at,updated_by)
SELECT g,'sk363-ready-'||g,g,1,1+(g-1)%1000,1,1+(g-1)%10000,'TOSS',
  timestamp '2026-11-11 19:00',timestamp '2026-11-07 19:00',10000,0,10000,'READY',
  LOCALTIMESTAMP,'sk363-fixture',LOCALTIMESTAMP,'sk363-fixture'
FROM generate_series(${history}+1,${history}+${ready}) g;

SELECT setval(pg_get_serial_sequence('p_payments','id'),(SELECT max(id) FROM p_payments));
SELECT setval(pg_get_serial_sequence('p_refunds','id'),coalesce((SELECT max(id) FROM p_refunds),1));
SELECT setval(pg_get_serial_sequence('p_settlement_items','id'),(SELECT max(id) FROM p_settlement_items));
