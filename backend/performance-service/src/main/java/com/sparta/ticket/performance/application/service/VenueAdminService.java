package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.application.dto.NewVenueSeat;
import com.sparta.ticket.performance.application.dto.RegisteredGrades;
import com.sparta.ticket.performance.application.dto.RegisteredSeats;
import com.sparta.ticket.performance.application.dto.VenueDetail;
import com.sparta.ticket.performance.application.port.VenueStore;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import com.sparta.ticket.performance.domain.venue.SeatPosition;
import com.sparta.ticket.performance.domain.venue.Venue;
import com.sparta.ticket.performance.domain.venue.VenueSeat;
import com.sparta.ticket.performance.domain.venue.VenueSeatGrade;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 공연장 배치 등록: 공연장 → 좌석 등급 → 물리 좌석. 명세는 .local/performance/api.md 5.2다.
 * 등급·좌석 등록은 공연장 행을 잠근 뒤 If-Match·사용 여부·중복을 확인하고, 성공하면 공연장 version을 1 올린다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VenueAdminService {

    private final VenueStore store;

    /** 공연장을 등록한다. version은 0에서 시작한다. */
    public VenueDetail register(String name, String address, String region) {
        return VenueDetail.from(store.save(Venue.create(name, address, region)));
    }

    /** 좌석 등급을 일괄 등록한다. 하나라도 실패하면 전체를 롤백한다. */
    public RegisteredGrades registerGrades(Long venueId, long expectedVersion, List<String> names) {
        // Note: 요청 안 이름 중복은 DB를 볼 필요 없는 형식 오류라 잠그기 전에 400으로 거절한다(명세).
        if (new HashSet<>(names).size() != names.size()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "요청 안에 같은 등급 이름이 있습니다.");
        }
        lockEditableVenue(venueId, expectedVersion);
        if (!Collections.disjoint(store.findGradeNames(venueId), names)) {
            throw new BusinessException(PerformanceErrorCode.DUPLICATE_VENUE_GRADE);
        }
        List<VenueSeatGrade> saved = store.saveGrades(
                names.stream().map(name -> VenueSeatGrade.create(venueId, name)).toList());
        return RegisteredGrades.of(saved, store.increaseVersion(venueId));
    }

    /** 물리 좌석을 일괄 등록한다. 위치가 하나라도 겹치면 전체를 롤백한다. */
    public RegisteredSeats registerSeats(Long venueId, long expectedVersion, List<NewVenueSeat> seats) {
        lockEditableVenue(venueId, expectedVersion);

        // Note: 요청만 보고 알 수 있는 것(요청 안 위치 중복)부터, DB와 비교해야 아는 것(등급 소속, 기존 위치)을 뒤에 본다.
        Set<SeatPosition> positions = new HashSet<>();
        for (NewVenueSeat seat : seats) {
            if (!positions.add(seat.position())) {
                throw new BusinessException(PerformanceErrorCode.DUPLICATE_VENUE_SEAT);
            }
        }
        Set<Long> gradeIds = seats.stream().map(NewVenueSeat::venueSeatGradeId).collect(Collectors.toSet());
        if (!store.findGradeIds(venueId).containsAll(gradeIds)) {
            throw new BusinessException(PerformanceErrorCode.VENUE_GRADE_MISMATCH);
        }
        Set<String> sections = seats.stream().map(NewVenueSeat::section).collect(Collectors.toSet());
        if (!Collections.disjoint(store.findSeatPositions(venueId, sections), positions)) {
            throw new BusinessException(PerformanceErrorCode.DUPLICATE_VENUE_SEAT);
        }

        List<VenueSeat> saved = store.saveSeats(seats.stream()
                .map(s -> VenueSeat.create(venueId, s.venueSeatGradeId(), s.section(), s.rowLabel(), s.seatNumber()))
                .toList());
        return new RegisteredSeats(saved.stream().map(VenueSeat::getId).toList(), store.increaseVersion(venueId));
    }

    /**
     * 공연장을 잠그고 배치를 바꿀 수 있는지 확인한다.
     * Note: 확인 순서는 404 → 409 VERSION_CONFLICT → 409 VENUE_LAYOUT_IN_USE다. 오래된 화면이면 먼저 다시 조회하게 한다.
     */
    private void lockEditableVenue(Long venueId, long expectedVersion) {
        Venue venue = store.findByIdForUpdate(venueId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.VENUE_NOT_FOUND));
        venue.verifyVersion(expectedVersion);
        if (store.isUsedByPerformance(venueId)) {
            throw new BusinessException(PerformanceErrorCode.VENUE_LAYOUT_IN_USE);
        }
    }
}
