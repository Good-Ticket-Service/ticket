package com.sparta.ticket.fakepg.application.service.settlement;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fake-pg.settlement")
public record FakeSettlementProperties(
        String version,
        LocalDate startDate,
        LocalDate coveredUntil,
        Set<LocalDate> holidays,
        Map<String, Fee> providers) {
    public record Fee(int rateBp, int vatRateBp, int businessDays) {
        public Fee {
            if (rateBp < 0
                    || rateBp > 5000
                    || vatRateBp < 0
                    || vatRateBp > 10000
                    || businessDays < 1
                    || businessDays > 20) throw new IllegalArgumentException("시험용 PG 정산 설정이 올바르지 않다");
        }
    }

    public FakeSettlementProperties {
        if (version == null
                || version.isBlank()
                || startDate == null
                || coveredUntil == null
                || startDate.isAfter(coveredUntil)
                || providers == null
                || !providers.keySet().containsAll(Set.of("TOSS", "KAKAO")))
            throw new IllegalArgumentException("시험용 정산 달력·요율이 없다");
        holidays = Set.copyOf(holidays == null ? Set.of() : holidays);
        providers = Map.copyOf(providers);
    }
}
