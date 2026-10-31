package com.sparta.ticket.payment.domain.exception.settlement;

import com.sparta.ticket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/** 판매자 정산 API 오류. If-Match 규칙은 공통 계약(docs/api.md)을 따른다: 없으면 428, version이 다르면 409. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {
    PAYOUT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "등록된 수취 계좌가 없습니다."),
    STATEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "정산서를 찾을 수 없습니다."),
    PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "지급을 찾을 수 없습니다."),
    PAYOUT_COMMAND_CONFLICT(HttpStatus.CONFLICT, "같은 명령 ID로 다른 요청을 보낼 수 없습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 요청이 먼저 변경했습니다. 최신 정보를 다시 조회해 주세요."),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "If-Match 헤더가 필요합니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
