package com.sparta.ticket.reservation.seat.presentation.dto;

import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import com.sparta.ticket.reservation.seat.application.dto.SeatSections.SeatSection;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 회차 구역 목록 응답(GET /api/seats/schedules/{scheduleId}/sections, #397). 시각은 초 단위, {@code +09:00} 오프셋 ISO 8601이다.
 * layout·status와 같은 스냅샷에서 만든다. 구역 순서는 구역의 가장 작은 좌석 id 순(공연장 좌석 등록 순서)이다.
 *
 * @param sections section은 좌석의 section 값 그대로다. 구역 layout·status를 받을 때 이 값을 URL 인코딩해 ?section=으로 보낸다
 */
public record SeatSectionsResponse(
        Long scheduleId, long configVersion, OffsetDateTime asOf, List<SeatSectionItem> sections) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SeatSectionsResponse from(SeatMapSnapshot snapshot) {
        return new SeatSectionsResponse(
                snapshot.result().scheduleId(),
                snapshot.configVersion(),
                snapshot.result()
                        .asOf()
                        .truncatedTo(ChronoUnit.SECONDS)
                        .atZone(SEOUL)
                        .toOffsetDateTime(),
                snapshot.sections().summaries().stream()
                        .map(SeatSectionItem::from)
                        .toList());
    }

    /** 구역 하나. selectableCount는 같은 스냅샷의 status 판정으로 센 선택 가능 좌석 수다. */
    public record SeatSectionItem(String section, int seatCount, int selectableCount) {

        static SeatSectionItem from(SeatSection section) {
            return new SeatSectionItem(section.section(), section.seatCount(), section.selectableCount());
        }
    }
}
