package com.sparta.ticket.payment.domain.model.settlement;

import java.util.Objects;

/** 분개의 업무 키. 같은 키는 원장에 한 번만 들어간다(UNIQUE). */
public record LedgerKey(LedgerSourceType sourceType, long sourceId, LedgerAction action) {

    public LedgerKey {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(action, "action");
    }
}
