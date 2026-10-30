package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 영업일 달력 버전 목록. 고르는 규칙과 기동 검증은 {@link SettlementPolicyBook}과 같다. */
public final class BusinessCalendarBook {

    private final List<BusinessCalendar> calendars;

    public BusinessCalendarBook(List<BusinessCalendar> calendars) {
        if (calendars == null || calendars.isEmpty()) throw new IllegalArgumentException("영업일 달력 버전이 하나도 없다");
        Set<String> versions = new HashSet<>();
        Set<LocalDateTime> starts = new HashSet<>();
        for (BusinessCalendar calendar : calendars) {
            if (!versions.add(calendar.version()))
                throw new IllegalArgumentException("영업일 달력 버전이 중복된다: " + calendar.version());
            if (!starts.add(calendar.effectiveFrom()))
                throw new IllegalArgumentException("영업일 달력 적용 시작 시각이 중복된다: " + calendar.effectiveFrom());
        }
        this.calendars = calendars.stream()
                .sorted(Comparator.comparing(BusinessCalendar::effectiveFrom))
                .toList();
    }

    public Optional<BusinessCalendar> at(LocalDateTime at) {
        BusinessCalendar selected = null;
        for (BusinessCalendar calendar : calendars) {
            if (calendar.effectiveFrom().isAfter(at)) break;
            selected = calendar;
        }
        return Optional.ofNullable(selected);
    }
}
