package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.PayoutProviderStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * REQUESTED 지급의 다음 조회 시각(settlement-design 7.2절, 2026-10-30 개정). 접수 여부를 모르는 UNKNOWN·inquiry_only는 짧은
 * 백오프로 빨리 확인하고, 지급대행이 접수를 확인한 ACCEPTED·PROCESSING은 송금 시점이 지급대행 쪽에 정해져 있으므로 예상 처리 시간
 * 뒤 긴 간격으로만 조회한다.
 * Note: 조회 횟수를 저장하지 않는다. UNKNOWN 대기는 최초 요청 뒤 지난 시간만큼(30초~300초)이라 횟수 없이도 대기가 기하급수로
 * 늘고, 재시작 뒤에도 같은 일정이 나온다.
 */
@Component
public class PayoutInquirySchedule {

    private final Duration unknownMin;
    private final Duration unknownMax;
    private final Duration slowAfter;
    private final Duration slowInterval;
    private final Duration acceptedFirst;
    private final Duration acceptedInterval;
    private final double jitterRatio;
    private final DoubleSupplier random;

    @Autowired
    public PayoutInquirySchedule(
            @Value("${payment.settlement.payout.inquiry.unknown-min-seconds:30}") long unknownMinSeconds,
            @Value("${payment.settlement.payout.inquiry.unknown-max-seconds:300}") long unknownMaxSeconds,
            @Value("${payment.settlement.payout.inquiry.slow-after-minutes:60}") long slowAfterMinutes,
            @Value("${payment.settlement.payout.inquiry.slow-interval-seconds:600}") long slowIntervalSeconds,
            @Value("${payment.settlement.payout.inquiry.accepted-first-minutes:60}") long acceptedFirstMinutes,
            @Value("${payment.settlement.payout.inquiry.accepted-interval-minutes:180}") long acceptedIntervalMinutes,
            @Value("${payment.settlement.payout.inquiry.jitter-ratio:0.2}") double jitterRatio) {
        this(
                Duration.ofSeconds(unknownMinSeconds),
                Duration.ofSeconds(unknownMaxSeconds),
                Duration.ofMinutes(slowAfterMinutes),
                Duration.ofSeconds(slowIntervalSeconds),
                Duration.ofMinutes(acceptedFirstMinutes),
                Duration.ofMinutes(acceptedIntervalMinutes),
                jitterRatio,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    PayoutInquirySchedule(
            Duration unknownMin,
            Duration unknownMax,
            Duration slowAfter,
            Duration slowInterval,
            Duration acceptedFirst,
            Duration acceptedInterval,
            double jitterRatio,
            DoubleSupplier random) {
        if (unknownMin.isNegative()
                || unknownMin.isZero()
                || unknownMax.compareTo(unknownMin) < 0
                || acceptedFirst.isNegative()
                || acceptedInterval.isNegative()
                || acceptedInterval.isZero()
                || slowInterval.isZero()
                || jitterRatio < 0
                || jitterRatio >= 1) throw new IllegalArgumentException("지급 조회 일정 설정이 잘못됐다");
        this.unknownMin = unknownMin;
        this.unknownMax = unknownMax;
        this.slowAfter = slowAfter;
        this.slowInterval = slowInterval;
        this.acceptedFirst = acceptedFirst;
        this.acceptedInterval = acceptedInterval;
        this.jitterRatio = jitterRatio;
        this.random = random;
    }

    /** 이번 조회·요청 결과를 반영한 뒤의 다음 조회 시각. */
    public LocalDateTime next(Payout payout, LocalDateTime now) {
        PayoutProviderStatus status = payout.getProviderStatus();
        boolean confirmed = !payout.isInquiryOnly()
                && (status == PayoutProviderStatus.ACCEPTED || status == PayoutProviderStatus.PROCESSING);
        if (confirmed) {
            LocalDateTime first = payout.getRequestedAt().plus(acceptedFirst);
            return now.isBefore(first) ? first : now.plus(jitter(acceptedInterval));
        }
        Duration elapsed = Duration.between(payout.getRequestedAt(), now);
        if (elapsed.compareTo(slowAfter) >= 0) return now.plus(jitter(slowInterval));
        Duration wait = elapsed.compareTo(unknownMin) < 0
                ? unknownMin
                : elapsed.compareTo(unknownMax) > 0 ? unknownMax : elapsed;
        return now.plus(jitter(wait));
    }

    private Duration jitter(Duration base) {
        double factor = 1 - jitterRatio + 2 * jitterRatio * random.getAsDouble();
        return Duration.ofMillis(Math.max(1, (long) (base.toMillis() * factor)));
    }
}
