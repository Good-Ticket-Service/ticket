package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.domain.venue.SeatPosition;
import com.sparta.ticket.performance.domain.venue.Venue;
import com.sparta.ticket.performance.domain.venue.VenueSeat;
import com.sparta.ticket.performance.domain.venue.VenueSeatGrade;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 공연장 배치(공연장, 좌석 등급, 좌석) 등록의 저장·조회 포트. 구현은 infrastructure가 맡는다. 모두 삭제되지 않은 행만 본다. */
public interface VenueStore {

    Venue save(Venue venue);

    /** 공연장을 행 잠금(FOR UPDATE)으로 읽는다. 같은 공연장의 배치 변경과 공연 초안 등록을 한 줄로 세운다. */
    Optional<Venue> findByIdForUpdate(Long venueId);

    /** 이 공연장을 참조하는 공연이 있는지. Note: 삭제된 공연도 센다(한 번 쓰인 배치는 고정). */
    boolean isUsedByPerformance(Long venueId);

    /** 공연장의 등급 이름 전체. */
    Set<String> findGradeNames(Long venueId);

    /** 공연장의 등급 ID 전체. */
    Set<Long> findGradeIds(Long venueId);

    /** 공연장에서 주어진 구역들에 있는 좌석 위치. 요청 좌석과의 중복 확인에 쓴다. */
    Set<SeatPosition> findSeatPositions(Long venueId, Collection<String> sections);

    /** 저장하고 바로 flush한다. 부분 UNIQUE 위반(동시 요청 안전망)은 409 DUPLICATE_VENUE_GRADE로 바꿔 던진다. */
    List<VenueSeatGrade> saveGrades(List<VenueSeatGrade> grades);

    /** 저장하고 바로 flush한다. 부분 UNIQUE 위반은 409 DUPLICATE_VENUE_SEAT로 바꿔 던진다. */
    List<VenueSeat> saveSeats(List<VenueSeat> seats);

    /** 공연장 version을 1 올리고 오른 값을 돌려준다. findByIdForUpdate로 잠근 뒤에만 부른다. */
    long increaseVersion(Long venueId);
}
