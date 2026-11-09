package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import java.util.function.Supplier;

/**
 * 결제를 끝내는 쪽(결제 결과 소비자, Outbox 발행기, 결제 중 집계)이 사용자 요청과 다른 DB 연결 풀을 쓰게 하는 표시.
 * 같은 풀을 쓰면 요청이 몰릴 때 결과 반영이 새 요청 스레드 수백 개와 같은 줄에서 연결을 기다려 확정이 멈춘다.
 * 측정에서 결제는 초당 150건 넘게 승인하는데 예매의 결과 반영은 초당 5~9건이었고, 부하가 끊기자 740건이었다.
 *
 * 이 안에서 시작한 트랜잭션은 전용 풀의 연결을 받는다. 트랜잭션이 끝날 때까지 표시가 유지되도록 호출 전체를 감싼다.
 */
public final class CompletionPool {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private CompletionPool() {}

    static boolean isActive() {
        return ACTIVE.get();
    }

    public static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    public static <T> T call(Supplier<T> work) {
        boolean before = ACTIVE.get();
        ACTIVE.set(true);
        try {
            return work.get();
        } finally {
            ACTIVE.set(before);
        }
    }
}
