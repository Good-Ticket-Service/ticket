package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.SellerScheduleRow;
import java.util.Optional;

/** 판매자 회차 조회. 구현은 infrastructure의 QueryDSL 저장소가 맡는다. */
public interface SellerScheduleReader {

    /**
     * 회차 하나와 부모 공연의 판매자·운영 상태. 회차나 부모 공연이 없거나 삭제됐으면 empty다.
     * 공개 여부와 취소 여부는 보지 않는다(초안 공연의 회차, 취소 회차도 돌려준다).
     */
    Optional<SellerScheduleRow> findSchedule(Long scheduleId);
}
