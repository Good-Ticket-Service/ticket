package com.sparta.ticket.performance.infrastructure.client;

/** 좌석 준비 200 응답. 내부 API라 ApiResponse로 감싸지 않는다. 값이 빠지면 계약과 다른 응답으로 본다. */
public record SeatSetupResponse(
        String requestId, Long scheduleId, Long configVersion, Integer seatCount, Integer sellableCount) {}
