package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.payment.application.dto.settlement.PayoutOperation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 실행자·기대 상태(FAILED)는 요청 본문에서 받지 않는다. note는 무엇을 확인했는지 남기는 운영 메모다. */
public record PayoutRetryRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String commandId,
        @NotBlank @Size(max = 200) String note,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @PositiveOrZero Integer expectedAttempt) {

    PayoutOperation.Retry toCommand(Long payoutId) {
        return new PayoutOperation.Retry(commandId, payoutId, note.strip(), expectedVersion, expectedAttempt);
    }
}
