package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldAttempt;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldState;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
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
 *   1. 이미 읽은 좌석으로 거른다. 판매 불가, 구성 버전·가격이 판매 검증과 다름, DB에서 팔렸거나 만료 전 HOLD인 좌석은 Redis에 기록하지 않는다.
 *      잠금 없이 읽은 값이라 최종 판정은 아니다. 결제하기 때 선점 트랜잭션이 좌석을 잠그고 다시 판정한다.
 *   2. 만료 시각을 공유 Clock으로 한 번 계산해 hold.lua로 기록한다. 이 값을 결제하기 때 DB 예약에도 그대로 쓴다(architecture 11절).
 *   3. 예약으로 바꾼 적 있는 회원이면 DB를 한 번 읽어 매수 제한을 확인한다(ConvertedUserCheck). 막을 예약이 없으면 회원 표시를 지우고
 *      한 번만 다시 기록한다. 그 사이 또 바뀌었으면 409다.
 */
@Service
@RequiredArgsConstructor
public class RedisSeatHoldService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 회원 표시를 지운 뒤 다시 기록하는 횟수. 같은 회원이 동시에 예약을 바꾸는 드문 경우라 한 번만 한다. */
    private static final int ATTEMPTS = 2;

    private final SeatHoldStore store;
    private final SeatHoldDuration holdDuration;
    private final ConvertedUserCheck convertedUserCheck;
    private final Clock clock;

    SeatHoldResult hold(Long userId, Long scheduleId, ScheduleSeat seat, SaleValidation validation) {
        Instant now = clock.instant();
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, clock.getZone());

        // 1. 좌석 자체 조건과 DB 점유. 선점 트랜잭션 4·5단계와 같은 판단을 잠금 없이 미리 한다
        if (!seat.isSellable()) {
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_SELLABLE);
        }
        int validatedPrice = validation.seats().get(0).basePrice();
        if (seat.getConfigVersion() != validation.configVersion()
                || !seat.getBasePrice().equals(validatedPrice)) {
            throw new BusinessException(ReservationErrorCode.SEAT_CONFIG_VERSION_MISMATCH);
        }
        if (seat.getStatus() == SeatStatus.SOLD) {
            throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
        }
        // 만료된 HOLD는 비어 있는 것으로 본다. 그 예약이 결제 중이면 결제하기 때 선점 트랜잭션이 409로 막는다
        Long heldInDbBy =
                seat.getStatus() == SeatStatus.HOLD && !seat.isHoldExpiredAt(nowLocal) ? seat.getReservationId() : null;

        // 2. 기록
        Instant expiresAt = now.plus(holdDuration.current());
        StoredHold hold = new StoredHold(
                newHoldId(), scheduleId, userId, seat.getId(), validation, expiresAt, HoldState.HELD, null);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            HoldAttempt result = store.hold(hold, heldInDbBy, now);
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
                    // 이 회원이 이미 예약으로 바꾼 좌석이다. DB 모드의 "같은 회원·같은 좌석"처럼 그 예약을 돌려준다
                    return new SeatHoldResult(
                            result.reservationId(),
                            seat.getId(),
                            seat.getHoldExpiresAt(),
                            seat.getBasePrice(),
                            false,
                            null);
                case TAKEN:
                    throw new BusinessException(ReservationErrorCode.SEAT_ALREADY_TAKEN);
                case LIMIT:
                    throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
                case CONVERTED_USER:
                    // 3. 예약으로 바꾼 적 있는 회원만 DB를 읽는다
                    Optional<SeatHoldResult> own =
                            convertedUserCheck.check(userId, scheduleId, seat.getId(), seat.getBasePrice(), nowLocal);
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

    private SeatHoldResult redisResult(String holdId, ScheduleSeat seat, Instant expiresAt, boolean created) {
        return new SeatHoldResult(
                null,
                seat.getId(),
                LocalDateTime.ofInstant(expiresAt, clock.getZone()),
                seat.getBasePrice(),
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
