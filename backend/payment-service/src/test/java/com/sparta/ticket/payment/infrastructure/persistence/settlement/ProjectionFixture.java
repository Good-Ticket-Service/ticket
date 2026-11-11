package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import org.springframework.jdbc.core.JdbcTemplate;

/** 성능 비교의 결정적 원장 fixture. 실제 checkout/PG 부하는 별도로 검증한다. */
final class ProjectionFixture {
    static final String PROFILE = "fixed-history-keys-v2";

    enum Distribution {
        UNIFORM,
        SELLER_SKEW,
        HOT_KEY
    }

    private ProjectionFixture() {}

    static void seed(JdbcTemplate jdbc, int fromSale, int facts, boolean historical, Distribution distribution) {
        if (facts <= 0 || facts % 100 != 0 || fromSale % 95 != 0)
            throw new IllegalArgumentException("95:5 입력 단위가 올바르지 않다");
        int sales = facts * 95 / 100;
        String base = "(CASE WHEN g%19=0 THEN 1+(g/19-1)%500 ELSE 501+(g-g/19-1)%9500 END)";
        String schedule = switch (distribution) {
            case HOT_KEY -> "CASE WHEN g%2=0 THEN 1 ELSE 1+" + base + " END";
            default -> base;
        };
        String seller = switch (distribution) {
            case UNIFORM -> "1+(" + base + "-1)%1000";
            case SELLER_SKEW ->
                "CASE WHEN (" + base + "-1)%100<80 THEN 1+(" + base + "-1)%10 ELSE 11+(" + base + "-1)%990 END";
            case HOT_KEY -> "CASE WHEN g%2=0 THEN 1 ELSE 2+(" + base + "-1)%999 END";
        };
        String approved = historical
                ? "timestamp '2026-10-11 09:00'+((" + base + "-1)%29)*interval '1 day'"
                : "timestamp '2026-11-10 09:00'+(g%3600)*interval '1 second'";
        if (distribution == Distribution.HOT_KEY)
            approved = "CASE WHEN g%2=0 THEN timestamp '2026-11-08 09:00' ELSE " + approved + " END";
        jdbc.update("""
                INSERT INTO p_payments(id,order_id,reservation_id,user_id,seller_id,performance_id,schedule_id,pg_provider,
                  expires_at,total_price,discount_amount,amount,status,approved_at,created_at,created_by,updated_at,updated_by)
                SELECT g,'projection334-'||g,g,1,%s,3,%s,'TOSS',(%s)+interval '1 hour',10000,
                  CASE WHEN (g/10)%%10=0 THEN 1000 ELSE 0 END,10000-CASE WHEN (g/10)%%10=0 THEN 1000 ELSE 0 END,
                  CASE WHEN g%%19=0 THEN 'CANCELED' ELSE 'DONE' END,%s,LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture'
                FROM generate_series(?::bigint,?::bigint) g
                """.formatted(seller, schedule, approved, approved), fromSale + 1, fromSale + sales);
        jdbc.update(
                """
                INSERT INTO p_refunds(id,payment_id,reservation_id,refund_amount,reason,status,refunded_at,
                  created_at,created_by,updated_at,updated_by)
                SELECT id,id,reservation_id,amount,'USER_CANCEL','DONE',approved_at+%s,
                  LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture' FROM p_payments WHERE id>? AND id<=? AND id%%19=0
                """.formatted(
                                distribution == Distribution.HOT_KEY
                                        ? "CASE WHEN id%2=0 THEN interval '10 minutes' ELSE "
                                                + (historical ? "interval '1 day'" : "interval '10 minutes'") + " END"
                                        : historical ? "interval '1 day'" : "interval '10 minutes'"),
                fromSale,
                fromSale + sales);
        jdbc.update("""
                INSERT INTO p_settlement_statements(id,seller_id,schedule_id,sequence,cutoff,sale_total,offset_total,
                  payable_amount,status,issued_at,created_at,created_by,updated_at,updated_by)
                SELECT p.id,p.seller_id,p.schedule_id,
                  coalesce((SELECT max(s.sequence) FROM p_settlement_statements s WHERE s.seller_id=p.seller_id AND s.schedule_id=p.schedule_id),0)
                    +row_number() OVER(PARTITION BY p.seller_id,p.schedule_id ORDER BY p.id),
                  p.approved_at+interval '5 minutes',CASE WHEN p.id%2=0 THEN 8680 ELSE 8900 END,0,
                  CASE WHEN p.id%2=0 THEN 8680 ELSE 8900 END,'ISSUED',p.approved_at+interval '5 minutes',
                  LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture' FROM p_payments p WHERE p.id>? AND p.id<=? AND p.id%38=0
                """, fromSale, fromSale + sales);
        jdbc.update("""
                INSERT INTO p_settlement_items(id,kind,status,seller_id,payment_id,refund_id,performance_id,schedule_id,approved_at,
                  gross_amount,discount_amount,booking_fee,paid_amount,commission,commission_vat,seller_amount,
                  booking_fee_net,booking_fee_vat,policy_version,calendar_version,eligible_at,statement_id,
                  created_at,created_by,updated_at,updated_by)
                SELECT id,'SALE',CASE WHEN id%38=0 THEN 'ON_STATEMENT' WHEN id%19=0 THEN 'CANCELED' ELSE 'PENDING' END,
                  seller_id,id,CASE WHEN id%19=0 THEN id END,performance_id,schedule_id,approved_at,10000,discount_amount,0,amount,
                  CASE WHEN id%2=0 THEN 1200 ELSE 1000 END,CASE WHEN id%2=0 THEN 120 ELSE 100 END,
                  CASE WHEN id%2=0 THEN 8680 ELSE 8900 END,0,0,CASE WHEN id%2=0 THEN 'fixture-old' ELSE 'fixture-new' END,
                  'fixture',approved_at+interval '1 minute',CASE WHEN id%38=0 THEN id END,
                  LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture' FROM p_payments WHERE id>? AND id<=?
                """, fromSale, fromSale + sales);
        jdbc.update("""
                INSERT INTO p_settlement_statement_lines(statement_id,item_id,kind,amount,created_at,created_by,updated_at,updated_by)
                SELECT statement_id,id,'SALE',seller_amount,LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture'
                FROM p_settlement_items WHERE kind='SALE' AND payment_id>? AND payment_id<=? AND statement_id IS NOT NULL
                """, fromSale, fromSale + sales);
        jdbc.update("""
                INSERT INTO p_settlement_items(id,kind,status,seller_id,payment_id,refund_id,origin_item_id,principal,remaining,
                  created_at,created_by,updated_at,updated_by)
                SELECT 1000000000+id,'DEDUCTION','OPEN',seller_id,payment_id,refund_id,id,seller_amount,seller_amount,
                  LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture' FROM p_settlement_items
                WHERE kind='SALE' AND payment_id>? AND payment_id<=? AND statement_id IS NOT NULL
                """, fromSale, fromSale + sales);
        jdbc.update("""
                INSERT INTO p_ledger_transactions(source_type,source_id,action,seller_id,payment_id,policy_version,
                  occurred_at,created_at,created_by,updated_at,updated_by)
                SELECT 'SALE',payment_id,'POST',seller_id,payment_id,policy_version,approved_at,
                  LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture' FROM p_settlement_items
                WHERE kind='SALE' AND payment_id>? AND payment_id<=?
                """, fromSale, fromSale + sales);
        jdbc.update("""
                INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
                SELECT t.id,v.line_no,v.account,v.debit,v.credit,LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture'
                FROM p_ledger_transactions t JOIN p_settlement_items s ON s.payment_id=t.payment_id AND s.kind='SALE'
                CROSS JOIN LATERAL(VALUES(1,'PG_CLEARING',s.paid_amount,0),(2,'DISCOUNT_EXPENSE',s.discount_amount,0),
                  (3,'SELLER_PAYABLE',0,s.seller_amount),(4,'COMMISSION_REVENUE',0,s.commission),(5,'VAT_PAYABLE',0,s.commission_vat))
                  v(line_no,account,debit,credit)
                WHERE t.source_type='SALE' AND t.payment_id>? AND t.payment_id<=? AND v.debit+v.credit>0
                """, fromSale, fromSale + sales);
        jdbc.update("""
                INSERT INTO p_ledger_transactions(source_type,source_id,action,seller_id,payment_id,refund_id,policy_version,
                  reverses_transaction_id,occurred_at,created_at,created_by,updated_at,updated_by)
                SELECT 'REFUND',r.id,CASE WHEN r.id%38=0 THEN 'RECEIVABLE' ELSE 'REVERSE' END,s.seller_id,r.payment_id,r.id,
                  s.policy_version,CASE WHEN r.id%38<>0 THEN t.id END,r.refunded_at,
                  LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture' FROM p_refunds r
                JOIN p_settlement_items s ON s.payment_id=r.payment_id AND s.kind='SALE'
                JOIN p_ledger_transactions t ON t.source_id=r.payment_id AND t.source_type='SALE' AND t.action='POST'
                WHERE r.payment_id>? AND r.payment_id<=?
                """, fromSale, fromSale + sales);
        jdbc.update("""
                INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
                SELECT r.id,e.line_no,CASE WHEN r.action='RECEIVABLE' AND e.account='SELLER_PAYABLE'
                  THEN 'SELLER_RECEIVABLE' ELSE e.account END,e.credit,e.debit,
                  LOCALTIMESTAMP,'fixture',LOCALTIMESTAMP,'fixture' FROM p_ledger_transactions r
                JOIN p_ledger_transactions s ON s.source_id=r.payment_id AND s.source_type='SALE' AND s.action='POST'
                JOIN p_ledger_entries e ON e.transaction_id=s.id
                WHERE r.source_type='REFUND' AND r.payment_id>? AND r.payment_id<=?
                """, fromSale, fromSale + sales);
    }
}
