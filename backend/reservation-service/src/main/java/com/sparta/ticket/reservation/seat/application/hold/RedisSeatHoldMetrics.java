package com.sparta.ticket.reservation.seat.application.hold;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Redis 선점 수(#449). 예매의 reservation.expired·reservation.released는 예약이 있어야 세므로, REDIS 모드에서는 예약으로 바꾼 것만
 * 센다. 두 모드를 비교하려고 Redis 선점의 선점·해제·결제하기 결과를 따로 센다.
 *
 * 지표: reservation.seat.hold.redis{operation=hold|release|convert, result=...}
 *   - hold: created, existing, mine_in_db, taken, limit, converted_user(DB 확인으로 넘어감), taken_in_db(DB 좌석 사전 검사에서 막음)
 *   - release: released, gone, forbidden, busy, converted
 *   - convert: created, existing, already_converted, expired, not_found, rejected(업무 거절), failed(트랜잭션 시작 실패),
 *     unknown(커밋했는지 모르는 오류), recovered(남은 시간이 모자란 재시도에서 앞선 요청의 예약을 찾음), busy(동시 처리 상한으로 거절)
 *
 * Note: Redis 키가 수명이 끝나 사라지는 만료는 알려 오는 이벤트가 없어 직접 세지 않는다. 측정에서는 선점 생성 − 해제 − 결제하기 −
 * 측정 끝에 남은 선점 수로 계산한다.
 */
@Component
@RequiredArgsConstructor
public class RedisSeatHoldMetrics {

    static final String NAME = "reservation.seat.hold.redis";

    private final MeterRegistry meters;

    public void hold(String result) {
        count("hold", result);
    }

    public void release(String result) {
        count("release", result);
    }

    public void convert(String result) {
        count("convert", result);
    }

    public static String tag(Enum<?> outcome) {
        return outcome.name().toLowerCase(Locale.ROOT);
    }

    private void count(String operation, String result) {
        meters.counter(NAME, "operation", operation, "result", result).increment();
    }
}
