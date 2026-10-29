package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.application.dto.PublicSeatLayout;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import com.sparta.ticket.performance.application.port.PublicSeatLayoutReader;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공개 공연의 정적 배치·등급 가격·공통 판매 제외를 조합한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicSeatLayoutService {

    private final PublicSeatLayoutReader reader;

    /**
     * 초안·삭제·미존재 공연과 삭제된 공연장의 공연은 404 PERFORMANCE_NOT_FOUND다.
     * 판매 대상 좌석의 가격이 하나라도 누락되면 배치 전체를 공통 500 INTERNAL_SERVER_ERROR로 처리한다.
     */
    public PublicSeatLayout getLayout(Long performanceId) {
        VenueSummary venue = reader.findPublicVenue(performanceId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
        List<PublicSeatLayout.Seat> seats = reader.findSeats(performanceId, venue.id());
        for (PublicSeatLayout.Seat seat : seats) {
            if (seat.isSellable() && seat.basePrice() == null) {
                log.error(
                        "공개 공연 판매 대상 좌석의 가격 누락: performanceId={}, seatId={}, venueSeatGradeId={}",
                        performanceId,
                        seat.id(),
                        seat.venueSeatGradeId());
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
        }
        return new PublicSeatLayout(performanceId, venue, seats);
    }
}
