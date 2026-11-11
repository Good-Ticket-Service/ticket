package com.sparta.ticket.reservation.seat.support;

import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties;
import com.sparta.ticket.reservation.seat.infrastructure.cache.SeatLayoutCompressExecutor;
import com.sparta.ticket.reservation.seat.infrastructure.cache.SeatSnapshotRefreshExecutor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * seat 통합 테스트의 공용 빈. ReservationIntegrationTest가 가져와 컨텍스트 하나에서 쓴다.
 * 판매 검증은 공연 서비스를 부르는 Feign 어댑터 대신 가짜를 우선 쓴다(@MockBean은 컨텍스트를 나눠 쓰지 않는다).
 * 잔여석·좌석 배치도 캐시의 만료 시계도 가짜로 바꿔, 기다리지 않고 TTL이 지난 상황을 만든다.
 */
@TestConfiguration
public class SeatTestConfig {

    @Bean
    @Primary
    public FakeSaleValidationPort fakeSaleValidationPort() {
        return new FakeSaleValidationPort();
    }

    /** 잔여석·좌석 배치도 캐시가 함께 쓰는 만료 시계. 테스트가 시간을 흘려 TTL이 지난 상황을 만든다. */
    @Bean
    @Primary
    public MutableTicker mutableTicker() {
        return new MutableTicker();
    }

    /** 선점 잠금 방식. 기본은 설정값(wait)이고, NOWAIT 테스트가 바꾼 뒤 되돌린다(#313). */
    @Bean
    @Primary
    public MutableSeatHoldLockMode mutableSeatHoldLockMode(SeatHoldProperties properties) {
        return new MutableSeatHoldLockMode(properties);
    }

    /** 좌석 배치도 캐시의 refresh·정리 작업. 테스트가 runPending()을 부를 때 실행된다(#311). */
    @Bean
    public DeferredTasks seatSnapshotRefreshTasks() {
        return new DeferredTasks();
    }

    /**
     * 좌석 배치도 캐시의 refresh를 전용 스레드 대신 쌓아 둔다(#311). refresh 뒤 첫 요청은 이전 스냅샷을, 테스트가 쌓인 작업을 실행한 뒤의
     * 요청은 새 스냅샷을 받는 것을 기다리지 않고 확인한다.
     */
    @Bean
    @Primary
    public SeatSnapshotRefreshExecutor deferredSeatSnapshotRefreshExecutor(DeferredTasks seatSnapshotRefreshTasks) {
        return SeatSnapshotRefreshExecutor.using(seatSnapshotRefreshTasks::execute);
    }

    /** layout 본문 압축 작업(#287). 테스트가 runPending()을 부를 때 실행된다. 이름으로 주입받는다(DeferredTasks 빈이 둘이다). */
    @Bean
    public DeferredTasks seatLayoutCompressTasks() {
        return new DeferredTasks();
    }

    /** layout 압축을 전용 스레드 대신 쌓아 둔다. 압축 전 요청은 원본을, 작업을 실행한 뒤의 요청은 압축본을 받는 것을 기다리지 않고 확인한다. */
    @Bean
    @Primary
    public SeatLayoutCompressExecutor deferredSeatLayoutCompressExecutor(DeferredTasks seatLayoutCompressTasks) {
        return SeatLayoutCompressExecutor.using(seatLayoutCompressTasks::execute);
    }
}
