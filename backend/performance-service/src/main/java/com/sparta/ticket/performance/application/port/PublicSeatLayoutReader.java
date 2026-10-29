package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.PublicSeatLayout;
import com.sparta.ticket.performance.application.dto.VenueSummary;
import java.util.List;
import java.util.Optional;

/** 공개 공연의 공연장 확인과 정적 좌석 배치 조회 포트. */
public interface PublicSeatLayoutReader {

    /** 공개 공연의 공연장 요약. 취소 공연도 포함하며 초안·삭제·미존재 공연과 삭제된 공연장의 공연은 empty다. */
    Optional<VenueSummary> findPublicVenue(Long performanceId);

    /**
     * 공개 공연 확인 후 그 공연장의 정적 좌석을 읽는다. 삭제 좌석·삭제 등급·다른 공연장 등급은 제외한다.
     * 삭제된 가격·판매 제외 행은 무시하고, 미삭제 제외 행이 있으면 isSellable=false다.
     * section, rowLabel 길이, rowLabel, seatNumber, id 순으로 정렬하며, 제외 좌석의 미설정 가격은 null이다.
     */
    List<PublicSeatLayout.Seat> findSeats(Long performanceId, Long venueId);
}
