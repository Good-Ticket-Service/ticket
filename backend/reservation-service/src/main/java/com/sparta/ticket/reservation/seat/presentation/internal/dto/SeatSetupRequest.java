package com.sparta.ticket.reservation.seat.presentation.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sparta.ticket.reservation.seat.application.setup.SeatSetupCommand;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 회차 좌석 준비 요청(api.md 「좌석 내부 API」, #175). 위반하면 공통 400 VALIDATION_ERROR다.
 * 길이·범위 제약은 좌석 DB 제약과 같다. 경로와 본문의 scheduleId가 같은지는 컨트롤러가 확인한다.
 *
 * @param seats 회차에 둘 좌석 전체. 일부만 보내지 않는다
 */
public record SeatSetupRequest(
        @NotBlank String requestId,
        @NotNull Long scheduleId,
        @NotNull @Min(1) Long configVersion,
        @NotEmpty List<@NotNull @Valid Line> seats) {

    /** 요청 안에서 같은 공연장 좌석을 두 번 보내면 거절한다. 목록이 비었거나 null이면 @NotEmpty가 따로 잡는다. */
    @JsonIgnore
    @AssertTrue(message = "seats의 venueSeatId가 겹치지 않아야 합니다.")
    public boolean isVenueSeatIdsDistinct() {
        if (seats == null) {
            return true;
        }
        List<Long> ids = seats.stream()
                .filter(Objects::nonNull)
                .map(Line::venueSeatId)
                .filter(Objects::nonNull)
                .toList();
        return new HashSet<>(ids).size() == ids.size();
    }

    public SeatSetupCommand toCommand() {
        return new SeatSetupCommand(
                requestId,
                scheduleId,
                configVersion,
                seats.stream().map(Line::toSpec).toList());
    }

    /**
     * 회차 좌석 한 개. basePrice는 판매 제외 좌석이면서 가격이 없을 때만 null이다.
     *
     * @param isSellable 공연 공통 판매 제외를 반영한 판매 대상 여부
     */
    public record Line(
            @NotNull Long venueSeatId,
            @NotNull Long venueSeatGradeId,
            @NotBlank @Size(max = 20) String gradeName,
            @Positive Integer basePrice,
            @NotNull Boolean isSellable,
            @NotBlank @Size(max = 20) String section,
            @NotBlank @Size(max = 10) String rowLabel,
            @NotNull @Min(1) Integer seatNumber) {

        /** 판매 대상이면 가격이 있어야 한다. isSellable이 null이면 @NotNull이 따로 잡는다. */
        @JsonIgnore
        @AssertTrue(message = "판매 대상 좌석에는 basePrice가 있어야 합니다.")
        public boolean isPricedWhenSellable() {
            return !Boolean.TRUE.equals(isSellable) || basePrice != null;
        }

        ScheduleSeatSpec toSpec() {
            return new ScheduleSeatSpec(
                    venueSeatId, venueSeatGradeId, gradeName, basePrice, section, rowLabel, seatNumber, isSellable);
        }
    }
}
