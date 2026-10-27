package com.sparta.ticket.fakepg.application.service.approval;

/** 승인 트랜잭션이 커밋한 결과. 오류 응답은 호출한 쪽이 커밋 후에 만든다. */
public enum ConfirmOutcome {
    APPROVED,
    REJECTED,
    DELAYED,
    EXPIRED
}
