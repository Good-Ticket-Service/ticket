-- 신규 원장 메타데이터와 트리거는 한 DDL 트랜잭션으로 전환한다. 기존 xid는 위조하지 않는다.
BEGIN;
ALTER TABLE p_ledger_transactions ADD COLUMN IF NOT EXISTS writer_xid xid8;
CREATE INDEX IF NOT EXISTS ix_ledger_writer_xid ON p_ledger_transactions(writer_xid) WHERE writer_xid IS NOT NULL;
CREATE OR REPLACE FUNCTION stamp_ledger_writer() RETURNS trigger LANGUAGE plpgsql AS '
BEGIN NEW.writer_xid:=pg_current_xact_id(); RETURN NEW; END';
CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS '
BEGIN RAISE EXCEPTION ''ledger is append only'' USING ERRCODE=''23514''; END';
CREATE OR REPLACE FUNCTION guard_ledger_entry() RETURNS trigger LANGUAGE plpgsql AS '
BEGIN
  IF NOT EXISTS(SELECT 1 FROM p_ledger_transactions WHERE id=NEW.transaction_id AND writer_xid=pg_current_xact_id()) THEN
    RAISE EXCEPTION ''ledger entries require the header writer transaction'' USING ERRCODE=''23514'';
  END IF;
  RETURN NEW;
END';
DROP TRIGGER IF EXISTS ledger_writer_stamp ON p_ledger_transactions;
CREATE TRIGGER ledger_writer_stamp BEFORE INSERT ON p_ledger_transactions FOR EACH ROW EXECUTE FUNCTION stamp_ledger_writer();
DROP TRIGGER IF EXISTS ledger_header_immutable ON p_ledger_transactions;
CREATE TRIGGER ledger_header_immutable BEFORE UPDATE OR DELETE ON p_ledger_transactions FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
DROP TRIGGER IF EXISTS ledger_entry_immutable ON p_ledger_entries;
CREATE TRIGGER ledger_entry_immutable BEFORE UPDATE OR DELETE ON p_ledger_entries FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
DROP TRIGGER IF EXISTS ledger_entry_writer ON p_ledger_entries;
CREATE TRIGGER ledger_entry_writer BEFORE INSERT ON p_ledger_entries FOR EACH ROW EXECUTE FUNCTION guard_ledger_entry();
CREATE TABLE IF NOT EXISTS p_reconcile_checkpoint (
  id INT PRIMARY KEY CHECK(id=1),generation BIGINT NOT NULL DEFAULT 0,
  valid BOOLEAN NOT NULL DEFAULT FALSE,snapshot TEXT,run_id BIGINT REFERENCES p_settlement_reconcile_runs,
  accepted_at TIMESTAMP, CHECK(NOT valid OR (snapshot IS NOT NULL AND run_id IS NOT NULL AND accepted_at IS NOT NULL))
);
INSERT INTO p_reconcile_checkpoint(id) VALUES(1) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS p_reconcile_account_balances (
  account VARCHAR(30) NOT NULL,scope VARCHAR(10) NOT NULL CHECK(scope IN ('SELLER','GLOBAL','UNKNOWN')),
  owner_id BIGINT NOT NULL,debit BIGINT NOT NULL,credit BIGINT NOT NULL,
  PRIMARY KEY(account,scope,owner_id),CHECK(debit>=0 AND credit>=0),
  CHECK(account IN ('SELLER_PAYABLE','SELLER_RECEIVABLE','PAYOUT_CLEARING'))
);
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS input_generation BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS output_generation BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS snapshot TEXT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS candidate_headers BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS delta_headers BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS delta_entries BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS changed_keys BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS cache_difference_count BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS cache_difference_sample TEXT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS cache_difference_debit NUMERIC NOT NULL DEFAULT 0;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS cache_difference_credit NUMERIC NOT NULL DEFAULT 0;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS baseline_reason VARCHAR(40);
COMMIT;
