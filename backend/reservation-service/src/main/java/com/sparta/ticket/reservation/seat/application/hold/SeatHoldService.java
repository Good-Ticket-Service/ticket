package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.SimpleErrorCode;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationPort;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.domain.SeatStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 좌석 선점(POST /api/seats/schedules/{scheduleId}/holds)의 진입점. 좌석 1장을 10분 동안 잡고 예약을 HOLD로 만든다.
 *
 * 처리 흐름
 *   1. 좌석을 잠금 없이 읽어 이 회차 좌석인지 확인한다(아니면 404 SEAT_NOT_FOUND).
 *   2. 공연 서비스의 판매 검증을 부른다. 원격 호출이라 트랜잭션 밖에서 부른다. 트랜잭션 안에서 부르면 공연 서비스가 느릴 때
 *      DB 연결과 좌석 행 잠금을 그동안 붙잡아 다른 선점까지 막는다.
 *   3. 좌석 잠금·예약 생성은 {@link SeatHoldTransactions}의 트랜잭션 하나에서 한다.
 *   4. 같은 회원이 같은 회차에 진행 중인 예약을 또 만들면 booking의 hold가 부분 UNIQUE 위반을 그대로 던진다. 트랜잭션이 롤백된 뒤
 *      여기서 제약 이름을 확인해 409 PURCHASE_LIMIT_EXCEEDED로 바꾼다(BookingFacade.hold 계약).
 *
 * Note: 이 클래스에는 트랜잭션이 없다. 1단계의 읽기는 저장소 기본 읽기 트랜잭션에서 끝나므로, 3단계 트랜잭션이 좌석을 잠글 때
 * 영속성 컨텍스트에 미리 읽은 좌석 Entity가 남아 있지 않다. 그래서 잠근 결과가 DB의 최신 값이다.
 */
@Service
@RequiredArgsConstructor
public class SeatHoldService {

    /** booking.sql의 활성 예매 부분 UNIQUE. 이 제약 위반만 매수 제한으로 바꾸고 다른 무결성 오류는 그대로 둔다. */
    static final String ACTIVE_RESERVATION_CONSTRAINT = "uk_p_reservations_active_user_schedule";

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SaleValidationPort saleValidationPort;
    private final SeatHoldTransactions transactions;

    public SeatHoldResult hold(Long userId, Long scheduleId, Long scheduleSeatId) {
        // 1. 경로의 회차 좌석인지 확인한다. 다른 회차 좌석을 받으면 그 회차의 판매 기간·매수 제한을 우회할 수 있다
        ScheduleSeat seat = scheduleSeatRepository
                .findById(scheduleSeatId)
                .filter(found -> found.getScheduleId().equals(scheduleId))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SEAT_NOT_FOUND));

        // 2. 판매 검증. 좌석 1장이고 구성 버전은 DB CHECK로 1 이상이라, 공연이 400으로 막는 빈 목록·중복·버전 0은 생기지 않는다
        SaleValidation validation =
                saleValidationPort.validate(scheduleId, seat.getConfigVersion(), List.of(seat.getVenueSeatId()));
        if (!validation.allowed()) {
            throw rejected(validation.reasonCode());
        }

        // 3. 읽은 시점의 점유 예약을 넘긴다. 잠근 뒤 만료됐으면 그 예약의 좌석 전체를 정리해야 해서 함께 잠그기 위해서다
        // 판매 완료 좌석은 잠근 뒤에도 409라 그 예약을 읽을 필요가 없어 HOLD일 때만 넘긴다
        SeatHoldTarget target =
                new SeatHoldTarget(seat.getId(), seat.getStatus() == SeatStatus.HOLD ? seat.getReservationId() : null);
        try {
            return transactions.hold(userId, scheduleId, target, validation);
        } catch (DataIntegrityViolationException e) {
            // 4. 동시에 들어온 같은 회원의 다른 좌석 선점이 먼저 커밋됐다. 다른 무결성 오류는 예상하지 못한 것이라 그대로 던진다
            if (violates(e, ACTIVE_RESERVATION_CONSTRAINT)) {
                throw new BusinessException(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED);
            }
            throw e;
        }
    }

    /**
     * 판매 검증 거절의 reasonCode를 그대로 외부 오류 code로 쓴다(docs/api.md 판매 거절 사유 표).
     * 좌석 자체의 문제인 SEAT_NOT_SELLABLE·SEAT_CONFIG_VERSION_MISMATCH는 409, 판매 기간·공연 상태 같은 나머지는 400이다.
     */
    private static BusinessException rejected(String reasonCode) {
        if (ReservationErrorCode.SEAT_NOT_SELLABLE.code().equals(reasonCode)) {
            return new BusinessException(ReservationErrorCode.SEAT_NOT_SELLABLE);
        }
        if (ReservationErrorCode.SEAT_CONFIG_VERSION_MISMATCH.code().equals(reasonCode)) {
            return new BusinessException(ReservationErrorCode.SEAT_CONFIG_VERSION_MISMATCH);
        }
        return new BusinessException(
                new SimpleErrorCode(HttpStatus.BAD_REQUEST, reasonCode, "지금은 이 회차의 좌석을 선점할 수 없습니다."));
    }

    /** 원인 중 Hibernate 제약 위반 예외를 찾아 제약 이름을 비교한다. */
    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
