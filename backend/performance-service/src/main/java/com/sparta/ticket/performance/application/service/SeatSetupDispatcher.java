package com.sparta.ticket.performance.application.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 좌석 준비를 언제 보낼지 정하는 한 곳. 회차 등록·가격 설정이 트랜잭션을 끝낸 뒤에 부른다.
 * Note: 1단계(동기, 이슈 #131)는 그 요청 스레드에서 바로 보낸다. 좌석이 느리면 판매자 응답도 그만큼 늦어진다(최대 connect + read
 * 타임아웃). 2단계(비동기)는 여기에 실행기를, 3단계(Kafka)는 Outbox 발행을 둔다(.local/performance/plan.md 2.2·2.3절).
 * 호출이 실패해도 회차는 이미 커밋됐으므로 예외를 판매자 응답으로 올리지 않는다. 남은 PENDING은 재시도 스케줄러가 보낸다.
 *
 * <p>Note: @TransactionalEventListener(AFTER_COMMIT)로 받지 않는다. 그 시점에는 커밋은 끝났지만 DB 커넥션을 아직 반납하지 않아
 * (Spring JPA의 커넥션 보유 방식) 좌석 호출 동안 커넥션을 쥐게 된다. 통합 테스트로 확인했다.
 */
@Component
@RequiredArgsConstructor
public class SeatSetupDispatcher {

    private final SeatSetupProcessor processor;

    /** 트랜잭션 밖에서만 부른다. 트랜잭션 안에서 부르면 좌석 호출 동안 커넥션을 쥔다. */
    public void dispatch(List<Long> scheduleIds) {
        scheduleIds.forEach(processor::processSafely);
    }
}
