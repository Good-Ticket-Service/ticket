package com.sparta.ticket.reservation.queue.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대기열(room) 생성 요청. 시각의 순서와 최소 여유는 WaitingRoomService가 검사한다.
 *
 * @param scopes 보호할 범위. "대상:ID" 형식(예: schedule:12)이다. 대기열은 뜻을 해석하지 않고, Gateway가 요청 URL과 비교한다.
 *     공연의 회차를 처음 만들 때 모두 넣는다(생성 뒤 바꿀 수 없다)
 * @param openAt 줄을 받기 시작하는 시각(오프셋 포함, 예: 2026-11-07T20:00:00+09:00)
 * @param closeAt 대기열이 끝나는 시각. 이때부터 줄을 받지 않고 Gateway도 검사를 멈춘다
 */
public record CreateWaitingRoomRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Pattern(regexp = "[a-z][a-z-]*:[0-9]+") String> scopes,
        @NotNull OffsetDateTime openAt,
        @NotNull OffsetDateTime closeAt) {}
