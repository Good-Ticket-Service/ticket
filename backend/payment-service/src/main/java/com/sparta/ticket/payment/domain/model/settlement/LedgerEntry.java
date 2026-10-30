package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분개 줄. 헤더(LedgerTransaction)와 같은 트랜잭션에서 저장하고 수정·삭제하지 않는다. */
@Getter
@Entity
@Table(name = "p_ledger_entries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerAccount account;

    @Column(nullable = false)
    private long debit;

    @Column(nullable = false)
    private long credit;

    LedgerEntry(int lineNo, LedgerLine line) {
        this.lineNo = lineNo;
        this.account = line.account();
        this.debit = line.debit();
        this.credit = line.credit();
    }

    public LedgerLine line() {
        return new LedgerLine(account, debit, credit);
    }
}
