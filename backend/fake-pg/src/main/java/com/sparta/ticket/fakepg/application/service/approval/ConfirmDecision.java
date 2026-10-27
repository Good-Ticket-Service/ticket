package com.sparta.ticket.fakepg.application.service.approval;

/** 승인 요청을 받았을 때 장애 설정으로 정한 처리 방식. 토스·카카오가 함께 쓴다. */
public enum ConfirmDecision {
    APPROVE,
    REJECT,
    DELAY
}
