package com.sparta.ticket.reservation.booking.presentation.dto;

import jakarta.validation.constraints.Size;

/**
 * 예약 취소 요청. 본문은 없어도 된다. 사유는 받기만 하고 저장하거나 넘기지 않는다.
 * 결제에는 사용자 취소라는 사유 코드(USER_CANCEL)만 보내기로 했기 때문이다(#127).
 */
public record CancelRequest(@Size(max = 500) String reason) {}
