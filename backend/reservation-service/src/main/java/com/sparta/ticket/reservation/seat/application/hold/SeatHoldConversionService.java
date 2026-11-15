package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.ConvertStart;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.domain.SeatStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 결제하기 때 Redis 선점을 DB 예약으로 바꾼다(#449). 예약이 생기는 것은 여기서부터라, 결제 준비·승인·만료·해제는 DB 모드와 같은 경로를 탄다.
 *
 * 처리 흐름
 *   1. Redis 선점을 CONVERTING으로 바꾼다. 바꾸는 동안 해제되지 않는다. 남은 시간이 MIN_REMAIN보다 적으면 409 HOLD_EXPIRED다.
 *   2. 좌석을 잠금 없이 읽어 함께 잠글 옛 예약을 고른다(SeatHoldService 1단계와 같은 이유).
 *   3. 선점 트랜잭션(SeatHoldTransactions.holdUntil)으로 예약을 만든다. 만료 시각과 판매 검증은 Redis 선점 때의 값이다.
 *      좌석 행 잠금과 재판정이 최종 판정이라, Redis 키가 사라져 두 회원이 같은 좌석을 잡았어도 예약은 한 건만 생긴다.
 *   4. 성공하면 Redis 좌석 키를 지우고 회원 키를 CONVERTED로 바꾼다. 실패하면 업무 거절은 Redis 선점을 지우고(다시 시도해도 같은 결과),
 *      트랜잭션을 시작하지 못한 오류는 HELD로 되돌리고, 커밋했는지 모르는 오류는 CONVERTING으로 둔다(해제는 409, 결제하기 재시도로
 *      커밋된 예약을 받거나 새로 만든다). 남은 시간이 모자란 재시도는 DB에서 앞선 요청의 예약을 찾는다.
 *
 * Note: 같은 선점을 동시에 두 번 바꾸면 둘 다 3단계에 들어간다. 좌석 잠금 뒤 "같은 회원의 만료 전 선점" 판정으로 뒤의 요청이 같은 예약을
 * 받으므로 예약은 한 건이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatHoldConversionService {

    /** 예약으로 바꿀 때 남아 있어야 하는 선점 시간. 결제 준비·승인을 시작할 틈이다. */
    static final Duration MIN_REMAIN = Duration.ofSeconds(3);

    private final SeatHoldStore store;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatHoldTransactions transactions;
    private final Clock clock;
    private final RedisSeatHoldMetrics metrics;
    private final RedisHoldDbReads dbReads;

    public SeatHoldResult convert(Long userId, Long scheduleId, String holdId) {
        if (!HoldIds.isHoldId(holdId)) {
            metrics.convert("not_found");
            throw new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND);
        }
        Instant now = clock.instant();

        // 1. 바꾸기 시작
        ConvertStart start = store.beginConvert(scheduleId, holdId, userId, now, MIN_REMAIN);
        switch (start.outcome()) {
            case NOT_FOUND, FORBIDDEN:
                // 남의 선점인지 드러내지 않는다. holdId는 추측할 수 없는 값이다
                metrics.convert("not_found");
                throw new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND);
            case EXPIRED:
                metrics.convert("expired");
                throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
            case CONVERTED:
                metrics.convert("already_converted");
                return converted(scheduleId, holdId, start.reservationId());
            case CONVERTING_EXPIRING:
                return convertingExpiring(userId, scheduleId, holdId, now);
            case STARTED:
                break;
            default:
                throw new IllegalStateException("알 수 없는 바꾸기 결과: " + start.outcome());
        }
        StoredHold hold = store.find(scheduleId, holdId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND));

        // 2. 함께 잠글 옛 예약
        ScheduleSeat seat = scheduleSeatRepository
                .findById(hold.scheduleSeatId())
                .filter(found -> found.getScheduleId().equals(scheduleId))
                .orElse(null);
        if (seat == null) {
            metrics.convert("rejected");
            abort(hold, true);
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_FOUND);
        }
        SeatHoldTarget target =
                new SeatHoldTarget(seat.getId(), seat.getStatus() == SeatStatus.HOLD ? seat.getReservationId() : null);
        LocalDateTime holdExpiresAt = LocalDateTime.ofInstant(hold.expiresAt(), clock.getZone());

        // 3. 예약 만들기
        SeatHoldResult result;
        try {
            result = transactions.holdUntil(userId, scheduleId, target, hold.validation(), holdExpiresAt);
        } catch (BusinessException e) {
            // 좌석이 이미 잡힘·매수 제한·판매 불가·구성 불일치·만료. 다시 시도해도 같으므로 선점을 지운다
            metrics.convert("rejected");
            abort(hold, true);
            throw e;
        } catch (DataIntegrityViolationException e) {
            if (SeatHoldService.violates(e, SeatHoldService.ACTIVE_RESERVATION_CONSTRAINT)) {
                metrics.convert("rejected");
                abort(hold, true);
                throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
            }
            metrics.convert("failed");
            abort(hold, false);
            throw e;
        } catch (CannotCreateTransactionException e) {
            // DB 연결을 얻지 못해 트랜잭션을 시작하지 못했다. 예약이 생기지 않은 것이 확실하므로 다시 결제하기를 누를 수 있게 되돌린다
            metrics.convert("failed");
            abort(hold, false);
            throw e;
        } catch (RuntimeException e) {
            // 커밋했는지 모른다(커밋 응답 유실 등). 되돌리면 해제가 Redis만 지워 DB 예약이 남을 수 있어 CONVERTING으로 둔다.
            // 해제는 409이고, 결제하기를 다시 누르면 DB가 커밋된 예약을 돌려주거나 새로 만든다
            metrics.convert("unknown");
            throw e;
        }

        metrics.convert(result.created() ? "created" : "existing");

        // 4. 완료 기록. DB는 이미 커밋했으므로 Redis가 실패해도 예약을 돌려준다. 남은 키는 선점 만료 때 사라진다
        try {
            store.finishConvert(hold, result.reservationId(), userKeyExpiresAt(hold), now);
        } catch (BusinessException e) {
            log.warn(
                    "예약으로 바꾼 뒤 Redis 완료 기록에 실패했다. 키는 선점 만료 때 사라진다. holdId={}, reservationId={}",
                    holdId,
                    result.reservationId());
        }
        return new SeatHoldResult(
                result.reservationId(),
                hold.scheduleSeatId(),
                result.holdExpiresAt(),
                result.basePrice(),
                result.created(),
                null);
    }

    /**
     * 바꾸는 중인데 남은 시간이 모자란 재시도. 앞선 요청이 결과를 모른 채 끝났을 수 있어, DB에서 이 회원·좌석의 만료 전 예약을 찾아
     * 있으면 그 예약을 돌려주고 완료를 기록한다. 없으면 409 HOLD_EXPIRED다. 남은 시간 1\~2초짜리 예약을 새로 만들지 않는다.
     * 없을 때 Redis 선점을 지우지 않는다. 앞선 요청이 아직 커밋 전일 수 있고, 키는 곧 수명이 끝난다.
     */
    private SeatHoldResult convertingExpiring(Long userId, Long scheduleId, String holdId, Instant now) {
        StoredHold hold = store.find(scheduleId, holdId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.HOLD_EXPIRED));
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, clock.getZone());
        SeatHoldResult own = dbReads.findOwnHold(userId, scheduleId, hold.scheduleSeatId(), hold.basePrice(), nowLocal)
                .orElse(null);
        if (own == null) {
            metrics.convert("expired");
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        metrics.convert("recovered");
        try {
            store.finishConvert(hold, own.reservationId(), userKeyExpiresAt(hold), now);
        } catch (BusinessException e) {
            log.warn("앞선 결제하기의 예약을 찾았지만 Redis 완료 기록에 실패했다. holdId={}, reservationId={}", holdId, own.reservationId());
        }
        return own;
    }

    /** 이미 바꾼 선점의 재요청. 같은 예약을 200으로 돌려준다. */
    private SeatHoldResult converted(Long scheduleId, String holdId, Long reservationId) {
        StoredHold hold = store.find(scheduleId, holdId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND));
        return new SeatHoldResult(
                reservationId,
                hold.scheduleSeatId(),
                LocalDateTime.ofInstant(hold.expiresAt(), clock.getZone()),
                hold.basePrice(),
                false,
                null);
    }

    /**
     * 회원 키(CONVERTED)를 둘 시각. 회차 시작까지 두어, 그 회원이 다시 선점할 때 DB를 확인하게 한다. 회차 시작을 모르거나
     * 선점 만료보다 이르면 선점 만료까지 둔다.
     */
    private Instant userKeyExpiresAt(StoredHold hold) {
        LocalDateTime startAt = hold.validation().scheduleStartAt();
        if (startAt == null) {
            return hold.expiresAt();
        }
        Instant start = startAt.atZone(clock.getZone()).toInstant();
        return start.isAfter(hold.expiresAt()) ? start : hold.expiresAt();
    }

    /** 실패 처리. Redis도 실패하면 원래 오류를 덮지 않고 로그만 남긴다. 선점은 만료 때 사라진다. */
    private void abort(StoredHold hold, boolean drop) {
        try {
            store.abortConvert(hold, drop);
        } catch (BusinessException e) {
            log.warn("예약으로 바꾸기 실패 뒤 Redis 정리에 실패했다. holdId={}, drop={}", hold.holdId(), drop);
        }
    }
}
