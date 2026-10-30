package com.sparta.ticket.performance.infrastructure.client;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import java.util.List;

/** POST /internal/schedules/{scheduleId}/seat-setups 요청 본문. 필드 이름은 계약 그대로다. */
public record SeatSetupRequest(String requestId, Long scheduleId, long configVersion, List<Seat> seats) {

    public record Seat(
            Long venueSeatId,
            Long venueSeatGradeId,
            String gradeName,
            Integer basePrice,
            boolean isSellable,
            String section,
            String rowLabel,
            int seatNumber) {}

    public static SeatSetupRequest from(SeatSetupCommand command) {
        return new SeatSetupRequest(
                command.requestId(),
                command.scheduleId(),
                command.configVersion(),
                command.seats().stream()
                        .map(seat -> new Seat(
                                seat.venueSeatId(),
                                seat.venueSeatGradeId(),
                                seat.gradeName(),
                                seat.basePrice(),
                                seat.sellable(),
                                seat.section(),
                                seat.rowLabel(),
                                seat.seatNumber()))
                        .toList());
    }
}
