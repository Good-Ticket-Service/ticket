package com.sparta.ticket.payment.domain.model.payment;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 실패 당시의 일정. 설정을 변경해도 기존 결제의 완료 단계 의미를 바꾸지 않는다. */
public record ReconcileSchedule(List<Long> minutes) {
    public ReconcileSchedule {
        if (minutes == null || minutes.isEmpty() || minutes.size() > 32) {
            throw new IllegalArgumentException("Reconcile 일정은 1~32개여야 한다");
        }
        long previous = 0;
        for (Long minute : minutes) {
            if (minute == null || minute <= previous || minute > 525_600) {
                throw new IllegalArgumentException("Reconcile 일정은 1년 이내의 양수·오름차순이어야 한다");
            }
            previous = minute;
        }
        minutes = List.copyOf(minutes);
    }

    public Duration checkpoint(int completed) {
        return Duration.ofMinutes(minutes.get(completed));
    }

    public String encode() {
        return minutes.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static ReconcileSchedule decode(String value) {
        return new ReconcileSchedule(
                Arrays.stream(value.split(",")).map(Long::valueOf).toList());
    }
}
