package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldAttempt;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldOutcome;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldState;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.domain.SeatHoldSnapshot;
import com.sparta.ticket.reservation.seat.domain.SeatStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * REDIS 모드의 좌석 선점(#449). 선점은 Redis에만 기록하고 DB에는 쓰지 않는다. 예약은 결제하기 때 만든다.
 *
 * 처리 흐름(좌석 읽기와 판매 검증은 SeatHoldService가 이미 했다)
 *   1. 이미 읽은 좌석으로 거른다. 판매 불가, 구성 버전·가격이 판매 검증과 다름이면 Redis에 기록하지 않는다.
 *   2. DB 점유 판정(#459). 이 회차를 Redis에 올렸으면 hold.lua가 Redis에 올린 DB 점유 좌석으로 판정한다. 아직 올리지 않았으면 올리기를
 *      요청하고, 이번 요청만 DB 좌석을 읽어 판정한다. DB에서 팔렸거나 만료 전 HOLD인 좌석, 만료됐지만 예약이 결제 중인 좌석은 기록하지 않는다.
 *      어느 쪽이든 잠금 없이 본 값이라 최종 판정은 아니다. 결제하기 때 선점 트랜잭션이 좌석을 잠그고 다시 판정한다.
 *   3. 만료 시각을 공유 Clock으로 한 번 계산해 hold.lua로 기록한다. 이 값을 결제하기 때 DB 예약에도 그대로 쓴다(architecture 11절).
 *   4. 예약으로 바꾼 적 있는 회원이면 DB를 한 번 읽어 매수 제한을 확인한다(RedisHoldDbReads). 막을 예약이 없으면 회원 표시를 지우고
 *      한 번만 다시 기록한다. 그 사이 또 바뀌었으면 409다.
 *
 * Note: Redis에 올린 DB 점유는 DB가 좌석을 풀 때(만료 스케줄러·해제·취소) Redis에서 지운다. 그래서 만료 시각이 지난 HOLD 좌석은 DB 판정보다
 * 만료 스케줄러 간격만큼 늦게 다시 잡을 수 있다(사용자 결정, 2026-11-17).
 */
@Service
@RequiredArgsConstructor
public class RedisSeatHoldService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 회원 표시를 지운 뒤 다시 기록하는 횟수. 같은 회원이 동시에 예약을 바꾸는 드문 경우라 한 번만 한다. */
    private static final int ATTEMPTS = 2;

    private final SeatHoldStore store;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatHoldDuration holdDuration;
    private final RedisHoldDbReads dbReads;
    private final RedisSeatHoldMetrics metrics;
    private final RedisSeatStateLoader stateLoader;
    private final RedisSeatStateReconciler reconciler;
    private final Clock clock;

    SeatHoldResult hold(Long userId, Long scheduleId, SeatHoldSnapshot seat, SaleValidation validation) {
        Instant now = clock.instant();
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, clock.getZone());

        // 1. 좌석 자체 조건. 선점 트랜잭션 4단계와 같은 판단을 잠금 없이 미리 한다
        if (!seat.sellable()) {
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_SELLABLE);
        }
        int validatedPrice = validation.seats().get(0).basePrice();
        if (seat.configVersion() != validation.configVersion()
                || !seat.basePrice().equals(validatedPrice)) {
            throw new BusinessException(ReservationErrorCode.SEAT_CONFIG_VERSION_MISMATCH);
        }

        // 2·3. DB 점유 판정과 기록
        Instant expiresAt = now.plus(holdDuration.current());
        StoredHold hold = new StoredHold(
                newHoldId(), scheduleId, userId, seat.scheduleSeatId(), validation, expiresAt, HoldState.HELD, null);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            HoldAttempt result = write(hold, seat, nowLocal, now);
            metrics.hold(RedisSeatHoldMetrics.tag(result.outcome()));
            switch (result.outcome()) {
                case CREATED:
                    return redisResult(result.holdId(), seat, expiresAt, true);
                case EXISTING:
                    // 같은 회원이 같은 좌석을 이미 잡았다. 만료 시각은 처음 기록한 값이다
                    Optional<StoredHold> existing = store.find(scheduleId, result.holdId());
                    if (existing.isPresent()) {
                        return redisResult(result.holdId(), seat, existing.get().expiresAt(), false);
                    }
                    // 읽기 직전에 만료됐다. 다시 기록한다
                    continue;
                case MINE_IN_DB:
                    // 이 회원이 이미 예약으로 바꾼 좌석이다. DB 모드의 "같은 회원·같은 좌석"처럼 만료 전 HOLD면 그 예약을 돌려준다.
                    // Redis 점유 판정은 그 예약이 팔렸거나 만료 뒤 정리 전인지 몰라 DB로 확인한다(같은 회원이 같은 좌석을 다시 누를 때만)
                    return dbReads.findOwnHold(userId, scheduleId, seat.scheduleSeatId(), seat.basePrice(), nowLocal)
                            .orElseThrow(() -> {
                                metrics.hold("taken_in_db");
                                return new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
                            });
                case TAKEN:
                    throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
                case LIMIT:
                    throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
                case CONVERTED_USER:
                    // 3. 예약으로 바꾼 적 있는 회원만 DB를 읽는다
                    Optional<SeatHoldResult> own = dbReads.checkConvertedUser(
                            userId, scheduleId, seat.scheduleSeatId(), seat.basePrice(), nowLocal);
                    if (own.isPresent()) {
                        return own.get();
                    }
                    store.clearConvertedUser(scheduleId, userId, result.reservationId());
                    continue;
                default:
                    throw new IllegalStateException("알 수 없는 선점 결과: " + result.outcome());
            }
        }
        throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
    }

    /**
     * DB 좌석 상태를 보지 않고 Redis 점유로 판정해 기록한다. 이 회차를 아직 올리지 않았으면 올리기를 요청하고, 이번 요청은 DB 좌석을
     * 읽어 판정해 기록한다.
     */
    private HoldAttempt write(StoredHold hold, SeatHoldSnapshot seat, LocalDateTime nowLocal, Instant now) {
        HoldAttempt result = store.hold(hold, now);
        if (result.outcome() != HoldOutcome.NOT_LOADED) {
            reconciler.track(hold.scheduleId());
            return result;
        }
        metrics.hold(RedisSeatHoldMetrics.tag(HoldOutcome.NOT_LOADED));
        stateLoader.requestLoad(hold.scheduleId());
        return store.hold(hold, heldInDb(seat.scheduleSeatId(), hold.scheduleId(), nowLocal), now);
    }

    /**
     * 아직 Redis에 올리지 않은 회차에서 DB 좌석을 읽어 본 DB 점유. 팔린 좌석과 만료됐지만 결제 중인 예약의 좌석은 409다. 만료 전 HOLD면 그 예약 ID, 비었으면 null이다.
     * 만료된 HOLD는 그 예약이 결제 중이 아니면 비어 있는 것으로 본다(정리 전인 만료 선점). 결제 중인 예약은 선점 만료 뒤에도 좌석을
     * 놓지 않으므로 409다. 여기서 막지 않으면 Redis 선점은 되지만 결제하기 때 409가 난다(#449 예매 담당 리뷰 1번)
     */
    private Long heldInDb(Long scheduleSeatId, Long scheduleId, LocalDateTime nowLocal) {
        SeatHoldSnapshot seat = scheduleSeatRepository
                .findForHold(scheduleSeatId, scheduleId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SEAT_NOT_FOUND));
        if (seat.status() == SeatStatus.SOLD) {
            metrics.hold("taken_in_db");
            throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
        }
        if (seat.status() != SeatStatus.HOLD) {
            return null;
        }
        if (!seat.isHoldExpiredAt(nowLocal)) {
            return seat.reservationId();
        }
        if (dbReads.paymentInProgress(seat.reservationId())) {
            metrics.hold("taken_in_db");
            throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
        }
        return null;
    }

    private SeatHoldResult redisResult(String holdId, SeatHoldSnapshot seat, Instant expiresAt, boolean created) {
        return new SeatHoldResult(
                null,
                seat.scheduleSeatId(),
                LocalDateTime.ofInstant(expiresAt, clock.getZone()),
                seat.basePrice(),
                created,
                holdId);
    }

    /** "h_" + 무작위 128비트 16진수. 추측할 수 없고, 숫자인 예약 ID와 섞이지 않는다. */
    private static String newHoldId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "h_" + HexFormat.of().formatHex(bytes);
    }
}
