package com.sparta.ticket.performance.support;

import java.util.concurrent.Executor;

/**
 * 통합 테스트에서 좌석 준비 실행기(SeatSetupDispatcher.EXECUTOR) 자리에 들어가는 위임 실행기(이슈 #466).
 * 기본은 호출 스레드에서 바로 실행한다. 그래서 기존 시험은 회차 등록·가격 설정 응답 직후 READY를 그대로 확인한다.
 * 비동기 시험만 시험 동안 위임 대상을 실제 실행기로 바꾸고, 끝나면 되돌린다. 그러면 MockMvc 요청이 실제 API →
 * 컨텍스트의 판매자 서비스 → 컨텍스트의 Dispatcher → 이 실행기 → 실제 비동기 실행기를 거친다.
 *
 * <p>Note: 위임 대상은 컨텍스트에 하나뿐인 공용 상태다. 통합 테스트가 하나씩 차례로 실행된다는 전제에 기댄다
 * (JUnit 병렬 실행 설정 없음, 2026-11-17 확인). 병렬 실행을 켜면 비동기 시험을 따로 격리해야 한다.
 */
public class SwitchableExecutor implements Executor {

    private static final Executor DIRECT = Runnable::run;

    private volatile Executor delegate = DIRECT;

    @Override
    public void execute(Runnable task) {
        delegate.execute(task);
    }

    public void use(Executor executor) {
        this.delegate = executor;
    }

    public void useDirect() {
        this.delegate = DIRECT;
    }
}
