package com.sparta.ticket.performance.presentation.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sparta.ticket.performance.presentation.support.RequestText;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 판매 중지·재개 요청. 중지(suspended=true)면 reason이 앞뒤 공백을 뺀 1~500자로 필수다.
 * 재개(false)면 reason을 생략하거나 null로 보낸다. 글자를 보내면 400이다(이슈 #133 결정 2: 클라이언트 실수를 조용히 넘기지 않는다).
 */
public record SalesSuspensionRequest(@NotNull Boolean suspended, String reason) {

    private static final int REASON_MAX_LENGTH = 500;

    /** 길이는 DB VARCHAR(500)과 같이 문자(코드 포인트) 수로 센다. suspended가 없으면 @NotNull이 따로 알린다. */
    @JsonIgnore
    @AssertTrue(message = "판매 중지에는 1~500자 사유가 필요하고, 판매 재개에는 사유를 보내지 않습니다.")
    public boolean isReason() {
        if (suspended == null) {
            return true;
        }
        if (!suspended) {
            return reason == null;
        }
        String stripped = RequestText.stripToNull(reason);
        return stripped != null && stripped.codePointCount(0, stripped.length()) <= REASON_MAX_LENGTH;
    }
}
