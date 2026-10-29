package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.application.dto.SaleSeat;
import com.sparta.ticket.performance.application.dto.SaleTarget;
import com.sparta.ticket.performance.application.dto.SaleValidationResult;
import com.sparta.ticket.performance.application.port.SaleValidationReader;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import com.sparta.ticket.performance.domain.schedule.SaleRejectReason;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매 서비스가 좌석을 선점하기 전에 부르는 판매 검증. 판매 정책과 가격만 확인하고 좌석 점유 여부는 보지 않는다.
 * 명세는 .local/performance/api.md 「POST /internal/schedules/{scheduleId}/sale-validations」다.
 * Note: 업무 거절은 예외가 아니라 allowed=false 결과다. 검증 직후 취소되는 경쟁은 이 API만으로 막지 못하며 별도 전파·보상으로 다룬다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SaleValidationService {

    private final SaleValidationReader reader;
    private final Clock clock;

    /**
     * 회차·공연 상태를 먼저 보고(SaleCondition), 통과하면 요청 좌석을 본다.
     * 회차·공연·공연장이 없거나 삭제됐으면 404 SCHEDULE_NOT_FOUND다.
     * 판매 대상 좌석에 공연 가격이 없으면 공개 조건상 생기면 안 되는 불변식 위반이라 500이다(좌석 배치와 같은 판단).
     *
     * @param venueSeatIds 중복 없는 공연장 좌석 ID. 응답 seats는 이 순서를 따른다.
     */
    public SaleValidationResult validate(Long scheduleId, long configVersion, List<Long> venueSeatIds) {
        SaleTarget target = reader.findTarget(scheduleId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.SCHEDULE_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<SaleRejectReason> reason = target.condition().rejectReason(now, configVersion);
        if (reason.isPresent()) {
            return SaleValidationResult.rejected(target, reason.get(), now);
        }

        Map<Long, SaleSeat> found = reader.findSeats(target.performanceId(), target.venueId(), venueSeatIds).stream()
                .collect(Collectors.toMap(SaleSeat::venueSeatId, Function.identity()));
        // 다른 공연장·삭제·등급 삭제로 조회되지 않은 좌석과 판매 제외 좌석은 팔 수 없다. 가격 확인보다 먼저 전부 본다.
        boolean allSellable = venueSeatIds.stream().map(found::get).allMatch(seat -> seat != null && seat.sellable());
        if (!allSellable) {
            return SaleValidationResult.rejected(target, SaleRejectReason.SEAT_NOT_SELLABLE, now);
        }

        List<SaleValidationResult.Seat> seats = new ArrayList<>(venueSeatIds.size());
        for (Long venueSeatId : venueSeatIds) {
            SaleSeat seat = found.get(venueSeatId);
            if (seat.basePrice() == null) {
                log.error(
                        "판매 대상 좌석의 가격 누락: scheduleId={}, performanceId={}, venueSeatId={}, venueSeatGradeId={}",
                        scheduleId,
                        target.performanceId(),
                        venueSeatId,
                        seat.venueSeatGradeId());
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            seats.add(new SaleValidationResult.Seat(
                    seat.venueSeatId(), seat.venueSeatGradeId(), seat.gradeName(), seat.basePrice()));
        }
        return SaleValidationResult.allowed(target, now, seats);
    }
}
