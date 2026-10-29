package com.sparta.ticket.reservation.seat.support;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationPort;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 통합 테스트용 판매 검증. 공연 서비스 대신 테스트가 정한 결과를 돌려준다.
 * 기본은 허용이며, 요청 좌석마다 등급 7·VIP·{@link #basePrice}와 요청한 구성 버전을 그대로 돌려준다.
 * 공용 컨텍스트 하나에서 쓰므로 테스트마다 {@link #reset()}으로 기본값으로 되돌린다.
 */
public class FakeSaleValidationPort implements SaleValidationPort {

    public static final Long PERFORMANCE_ID = 11L;
    public static final Long SELLER_ID = 1001L;
    public static final int DEFAULT_PRICE = 150_000;
    public static final LocalDateTime SCHEDULE_START_AT = LocalDateTime.of(2026, 11, 30, 19, 0);
    public static final String PERFORMANCE_TITLE = "오페라의 유령";

    private volatile int basePrice = DEFAULT_PRICE;
    private volatile Long configVersionOverride;
    private volatile String rejectReason;
    private volatile BusinessException failure;
    private volatile Runnable duringValidation = () -> {};
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public SaleValidation validate(Long scheduleId, long configVersion, List<Long> venueSeatIds) {
        calls.incrementAndGet();
        duringValidation.run();
        if (failure != null) {
            throw failure;
        }
        if (rejectReason != null) {
            return new SaleValidation(
                    false,
                    rejectReason,
                    scheduleId,
                    PERFORMANCE_ID,
                    SELLER_ID,
                    configVersion,
                    1L,
                    SCHEDULE_START_AT,
                    PERFORMANCE_TITLE,
                    List.of());
        }
        long answeredVersion = configVersionOverride != null ? configVersionOverride : configVersion;
        List<SaleValidationSeat> seats = venueSeatIds.stream()
                .map(id -> new SaleValidationSeat(id, 7L, "VIP", basePrice))
                .toList();
        return new SaleValidation(
                true,
                null,
                scheduleId,
                PERFORMANCE_ID,
                SELLER_ID,
                answeredVersion,
                1L,
                SCHEDULE_START_AT,
                PERFORMANCE_TITLE,
                seats);
    }

    public void reset() {
        basePrice = DEFAULT_PRICE;
        configVersionOverride = null;
        rejectReason = null;
        failure = null;
        duringValidation = () -> {};
        calls.set(0);
    }

    /** 허용하되 공연 쪽 가격을 바꾼다. 좌석 스냅샷 가격과 다르면 선점은 SEAT_CONFIG_VERSION_MISMATCH다. */
    public void price(int basePrice) {
        this.basePrice = basePrice;
    }

    /** 허용하되 공연 쪽 최신 구성 버전을 바꾼다. */
    public void configVersion(long configVersion) {
        this.configVersionOverride = configVersion;
    }

    /** allowed=false와 이 reasonCode로 거절한다. */
    public void reject(String reasonCode) {
        this.rejectReason = reasonCode;
    }

    /** 호출하면 이 예외를 던진다(404 전달·503 등). */
    public void fail(BusinessException failure) {
        this.failure = failure;
    }

    /**
     * 판매 검증을 기다리는 동안 일어날 일을 정한다. 예를 들어 시계를 앞으로 돌려 "검증 중에 선점이 만료된" 경계를 만든다.
     */
    public void duringValidation(Runnable action) {
        this.duringValidation = action;
    }

    public int calls() {
        return calls.get();
    }
}
