package com.sparta.ticket.performance.application.service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 좌석 준비를 언제 보낼지 정하는 한 곳. 회차 등록·가격 설정이 트랜잭션을 끝낸 뒤에 부른다.
 * Note: 2단계(비동기, 이슈 #466)다. 회차마다 작업을 좌석 준비 전용 실행기에 넘기고 바로 돌아온다. 판매자 응답은 좌석 응답을
 * 기다리지 않는다. 1단계는 요청 스레드에서 동기로 보냈다(이슈 #131, 측정은 이슈 #406). 3단계(Kafka)는 여기에 Outbox 발행을 둔다(.local/performance/plan.md 2.2·2.3절).
 * 실행기가 받지 못한 작업(큐 가득 참)은 버린다. 회차는 이미 PENDING으로 커밋돼 있어 재시도 스케줄러가 보낸다.
 * 서버가 꺼지며 큐에 남은 작업이 사라져도 같다.
 *
 * <p>Note: 좌석 호출의 동시 실행 수는 인스턴스당 실행기 스레드 수(기본 1) + 스케줄링 스레드 수(기본 1, 재시도·복구 스케줄러가 함께 씀)다.
 * 좌석 준비가 예매 서비스의 선점·결제와 같은 DB 연결 풀을 쓰므로 작게 둔다(이슈 #406 합의). 둘 중 하나를 늘리면 상한도 늘어난다.
 *
 * <p>Note: @TransactionalEventListener(AFTER_COMMIT)로 받지 않는다. 그 시점에는 커밋은 끝났지만 DB 커넥션을 아직 반납하지 않는다
 * (Spring JPA의 커넥션 보유 방식). 트랜잭션 안에서 부르면 실행기 스레드가 커밋 전 회차를 읽을 수도 있다.
 */
@Slf4j
@Component
public class SeatSetupDispatcher {

    /** 좌석 준비 전용 실행기 빈 이름. 설정은 infrastructure의 SeatSetupConfig다. */
    public static final String EXECUTOR = "seatSetupExecutor";

    private final SeatSetupProcessor processor;
    private final Executor executor;

    public SeatSetupDispatcher(SeatSetupProcessor processor, @Qualifier(EXECUTOR) Executor executor) {
        this.processor = processor;
        this.executor = executor;
    }

    /** 트랜잭션 밖(커밋 뒤)에서만 부른다. 예외를 호출한 쪽(커밋된 판매자 요청)으로 올리지 않는다. */
    public void dispatch(List<Long> scheduleIds) {
        for (Long scheduleId : scheduleIds) {
            try {
                executor.execute(() -> processor.processSafely(scheduleId));
            } catch (RejectedExecutionException e) {
                log.warn("좌석 준비 실행기가 작업을 받지 못했다. 재시도 스케줄러가 보낸다. scheduleId={}", scheduleId);
            }
        }
    }
}
