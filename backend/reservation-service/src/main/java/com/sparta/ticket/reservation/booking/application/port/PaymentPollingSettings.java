package com.sparta.ticket.reservation.booking.application.port;

import java.time.Duration;

/** 결제 중 예약을 조회한 클라이언트에게 알려 줄 다음 조회까지의 간격. */
public interface PaymentPollingSettings {

    /** 결제 중 예약이 적을 때의 간격. */
    Duration base();

    /** 아무리 밀려도 이보다 길게 기다리게 하지 않는다. 결과를 아는 시간이 그만큼 늦어지기 때문이다. */
    Duration max();

    /** 결제 중 예약 수가 이 값의 배수를 넘을 때마다 간격을 두 배로 늘린다. */
    long busyAbove();
}
