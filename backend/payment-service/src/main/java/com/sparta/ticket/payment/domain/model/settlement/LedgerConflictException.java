package com.sparta.ticket.payment.domain.model.settlement;

/**
 * 같은 업무 키로 이미 다른 내용의 분개가 있다. 정책·금액·원거래가 바뀐 재처리를 중복으로 숨기지 않고 드러낸다.
 * 메시지에는 업무 키만 넣는다.
 */
public class LedgerConflictException extends IllegalStateException {

    public LedgerConflictException(LedgerKey key) {
        super("같은 업무 키로 다른 분개가 이미 있다: " + key);
    }
}
