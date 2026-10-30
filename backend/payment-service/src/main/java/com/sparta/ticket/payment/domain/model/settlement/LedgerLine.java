package com.sparta.ticket.payment.domain.model.settlement;

import java.util.Objects;

/** 분개 한 줄. 차변·대변 중 한쪽만 양수다. 0원 줄과 음수는 만들지 않는다. */
public record LedgerLine(LedgerAccount account, long debit, long credit) {

    public LedgerLine {
        Objects.requireNonNull(account, "account");
        if (debit < 0 || credit < 0 || (debit > 0) == (credit > 0))
            throw new IllegalArgumentException("분개 줄은 차변·대변 중 한쪽만 양수다");
    }

    public static LedgerLine debit(LedgerAccount account, long amount) {
        return new LedgerLine(account, amount, 0);
    }

    public static LedgerLine credit(LedgerAccount account, long amount) {
        return new LedgerLine(account, 0, amount);
    }

    public boolean isDebit() {
        return debit > 0;
    }

    /** 같은 계정·금액을 반대쪽으로. 반대 분개에 쓴다. */
    public LedgerLine flipped() {
        return new LedgerLine(account, credit, debit);
    }
}
