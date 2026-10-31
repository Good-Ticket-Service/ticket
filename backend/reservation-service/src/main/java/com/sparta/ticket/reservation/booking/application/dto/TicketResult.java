package com.sparta.ticket.reservation.booking.application.dto;

/** 예약이 담은 좌석 한 장. ticketCode는 확정한 예약에서만 있다. */
public record TicketResult(Long scheduleSeatId, int price, String ticketCode) {}
