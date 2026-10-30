package com.sparta.ticket.payment.domain.model.settlement;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 영업일 달력 한 버전. 주말과 설정한 공휴일이 아니면 영업일이다. 공휴일을 아는 마지막 날(coveredUntil)을 넘는 날짜는
 * 영업일인지 단정하지 않는다. 달력을 늘리지 않은 채 정산 가능일을 추측하지 않게 하기 위해서다.
 */
public record BusinessCalendar(
        String version, LocalDateTime effectiveFrom, LocalDate coveredUntil, Set<LocalDate> holidays) {

    public BusinessCalendar {
        if (version == null || version.isBlank() || version.length() > 40)
            throw new IllegalArgumentException("영업일 달력 버전은 1~40자다");
        Objects.requireNonNull(effectiveFrom, "영업일 달력 적용 시작 시각이 없다");
        Objects.requireNonNull(coveredUntil, "영업일 달력이 아는 마지막 날이 없다");
        holidays = Set.copyOf(holidays == null ? Set.of() : holidays);
        if (holidays.stream().anyMatch(day -> day.isAfter(coveredUntil)))
            throw new IllegalArgumentException("공휴일이 달력 범위를 넘는다: " + version);
    }

    public boolean isBusinessDay(LocalDate day) {
        DayOfWeek dayOfWeek = day.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    /**
     * 정산 가능 시각: 회차 시작일(KST) 다음 날부터 세어 businessDays번째 영업일의 00:00.
     * 그 날이 달력 범위를 넘으면 비어 있다.
     */
    public Optional<LocalDateTime> eligibleAt(LocalDateTime scheduleStartAt, int businessDays) {
        LocalDate day = scheduleStartAt.toLocalDate();
        int counted = 0;
        while (counted < businessDays) {
            day = day.plusDays(1);
            if (day.isAfter(coveredUntil)) return Optional.empty();
            if (isBusinessDay(day)) counted++;
        }
        return Optional.of(day.atStartOfDay());
    }
}
