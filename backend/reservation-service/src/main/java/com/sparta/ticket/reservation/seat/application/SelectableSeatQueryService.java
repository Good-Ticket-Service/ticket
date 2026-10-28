package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatResult;
import com.sparta.ticket.reservation.seat.application.dto.SelectableSeatsResult;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.domain.SeatSelectability;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 배치도의 선택 가능 좌석 조회.
 *
 * Note: 좌석을 잠그지 않고 읽으므로 응답을 받은 사이에 다른 회원이 선점할 수 있다. 이 조회는 화면에 보여 줄 판정일 뿐이고,
 * 실제로 선점할 수 있는지는 선점 API가 좌석을 잠근 뒤 다시 판정한다.
 *
 * Note: 클래스 전체를 읽기 전용 트랜잭션으로 둔다. 결제 중 여부를 묻는 BookingFacade가 MANDATORY라 트랜잭션 안에서만 부를 수 있고,
 * 좌석과 예약을 같은 시점의 스냅샷에서 읽게 하려는 목적도 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SelectableSeatQueryService {

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final BookingFacade bookingFacade;
    private final Clock clock;

    public SelectableSeatsResult getSelectableSeats(Long scheduleId) {
        // 1. 회차 좌석을 읽는다. 좌석이 하나도 없으면 없는 회차인지 좌석 준비 전인지 seat는 알 수 없어(공연 테이블을 보지 않음) 둘 다 404로 답한다.
        List<ScheduleSeat> seats = scheduleSeatRepository.findAllBySchedule(scheduleId);
        if (seats.isEmpty()) {
            throw new BusinessException(ReservationErrorCode.SEAT_NOT_FOUND);
        }

        // 2. 판정 시각은 seat·booking이 함께 쓰는 Clock에서 한 번만 읽는다. 좌석마다 다른 시각으로 판정하면 만료 경계가 어긋난다.
        LocalDateTime now = LocalDateTime.now(clock);
        List<SeatSelectability> selectabilities =
                seats.stream().map(seat -> seat.selectabilityAt(now)).toList();

        // 3. 만료된 HOLD는 그 예약이 결제 중이면 선택할 수 없다. 예약 상태는 booking이 갖고 있으므로,
        //    만료 HOLD의 예약 id를 모아 한 번에 묻는다. 좌석마다 묻거나 예약 테이블을 JOIN하지 않는다(모듈 경계).
        //    이 확인은 화면 표시용 최적화이고, 실제 재선점은 선점 API에서 expireIfDue 조건부 변경으로 다시 막는다.
        List<Long> expiredHoldReservationIds = expiredHoldReservationIds(seats, selectabilities);
        Set<Long> paymentInProgress = expiredHoldReservationIds.isEmpty()
                ? Set.of()
                : bookingFacade.findPaymentInProgress(expiredHoldReservationIds);

        // 4. 좌석별 최종 판정. 응답에는 다른 회원의 예약 id·만료 시각과 판매 제외 사유를 넣지 않는다.
        List<SelectableSeatResult> results = new ArrayList<>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            ScheduleSeat seat = seats.get(i);
            boolean selectable = switch (selectabilities.get(i)) {
                case SELECTABLE -> true;
                case NOT_SELECTABLE -> false;
                case EXPIRED_HOLD -> !paymentInProgress.contains(seat.getReservationId());
            };
            results.add(toResult(seat, selectable));
        }

        // 5. 좌석 준비는 회차 좌석을 한 번에 같은 구성 버전으로 만들고 교체하므로, 첫 좌석의 버전이 회차의 버전이다.
        return new SelectableSeatsResult(scheduleId, seats.get(0).getConfigVersion(), now, results);
    }

    /**
     * 만료됐지만 아직 정리되지 않은 HOLD 좌석의 예약 id를 모은다.
     * 이 예약들이 결제 중인지 booking에 한 번에 묻기 위한 목록이다.
     *
     * @param seats 회차 좌석
     * @param selectabilities seats와 같은 순서로 계산한 좌석별 판정
     * @return 중복 없는 예약 id 목록. 만료 HOLD가 없으면 빈 목록
     */
    private static List<Long> expiredHoldReservationIds(
            List<ScheduleSeat> seats, List<SeatSelectability> selectabilities) {
        List<Long> ids = new ArrayList<>();
        // seats와 selectabilities는 같은 순서로 만들었으므로 같은 인덱스가 같은 좌석이다.
        for (int i = 0; i < seats.size(); i++) {
            // 결제 중인지에 따라 답이 달라지는 좌석은 만료 HOLD뿐이다.
            // 비어 있는 좌석과 유효한 선점·판매 완료 좌석은 예약 상태와 관계없이 판정이 끝났다.
            if (selectabilities.get(i) == SeatSelectability.EXPIRED_HOLD) {
                ids.add(seats.get(i).getReservationId());
            }
        }
        // Note: 한 예약이 좌석을 여러 개 잡을 수 있어(2차 여러 장 예매) 같은 id가 반복될 수 있다.
        // 중복을 빼야 booking에 묻는 목록이 짧아지고, IN 조건에 같은 값이 반복되지 않는다.
        return ids.stream().distinct().toList();
    }

    private static SelectableSeatResult toResult(ScheduleSeat seat, boolean selectable) {
        return new SelectableSeatResult(
                seat.getId(),
                seat.getVenueSeatId(),
                seat.getSection(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                seat.getVenueSeatGradeId(),
                seat.getGradeName(),
                seat.getBasePrice(),
                selectable);
    }
}
