package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.SaleSeat;
import com.sparta.ticket.performance.application.dto.SaleTarget;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 판매 검증에 필요한 회차·공연·좌석 조회. 구현은 infrastructure의 QueryDSL 저장소가 맡는다. */
public interface SaleValidationReader {

    /**
     * 삭제되지 않은 회차와 부모 공연. 회차·공연·공연장 중 하나라도 없거나 삭제됐으면 empty다.
     * 초안·취소 공연과 취소 회차는 돌려준다(거절 사유로 판단한다).
     */
    Optional<SaleTarget> findTarget(Long scheduleId);

    /**
     * 요청한 좌석 중 그 공연장에 속하고 삭제되지 않은 좌석만 돌려준다. 등급이 삭제된 좌석도 뺀다.
     * 삭제되지 않은 공연 공통 판매 제외 행이 있으면 sellable=false, 그 공연에 등급 가격이 없으면 basePrice=null이다.
     */
    List<SaleSeat> findSeats(Long performanceId, Long venueId, Collection<Long> venueSeatIds);
}
