package com.sparta.ticket.reservation.booking.application.port;

import java.time.Duration;

/** 메시지 승인에서 결제 결과가 계속 늦어질 때 결제 준비를 잠시 받지 않는 기준. */
public interface AdmissionSettings {

    boolean enabled();

    /** 접수 후 이 시간이 지나도 결과가 없는 결제 중 예약을 늦은 결제로 센다. */
    Duration lateAfter();

    /** 늦은 결제가 이보다 많고 계속 늘면 거절을 시작한다. */
    int shedAbove();

    /** 거절 중에 늦은 결제가 이 수 이하로 내려오면 다시 받는다. */
    int resumeBelow();

    /** 늦은 결제가 계속 느는지 보는 창. 창의 앞 절반과 뒤 절반에서 모두 늘어야 계속 는다고 본다. */
    Duration trendWindow();

    /** 거절 응답의 가장 짧은 Retry-After. 기다리는 사용자가 많으면 늘린다. */
    Duration retryAfter();

    /** 늘린 Retry-After의 최대. */
    Duration maxRetryAfter();

    /** 늦은 결제 신호가 이 시간 넘게 갱신되지 않으면 막지 않는다. */
    Duration staleAfter();

    /** 거절 중에도 선점 만료가 이 시간 안에 오는 결제 준비는 통과시킨다. */
    Duration priorityWindow();

    /** 만료 임박 통과를 인스턴스마다 초당 이 수까지만 허용한다. */
    int priorityRate();
}
