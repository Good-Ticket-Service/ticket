package com.sparta.ticket.fakepg.presentation.dto.admin;

/**
 * 외부 취소 요청. cancelAmount를 비우면 전액 취소다. reason을 비우면 "외부 취소"로 기록한다.
 */
public record ExternalCancelRequest(Integer cancelAmount, String reason) {

    @Override
    public String reason() {
        return reason == null || reason.isBlank() ? "외부 취소" : reason;
    }
}
