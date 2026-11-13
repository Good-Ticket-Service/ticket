package com.sparta.ticket.reservation.seat.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.seat.application.dto.SeatSections.SeatSection;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 스냅샷을 구역(section)별로 나누는 규칙(#397). */
class SeatSectionsTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 11, 12, 20, 0);

    private static SelectableSeatResult seat(long id, String section, int number, long gradeId, boolean selectable) {
        return new SelectableSeatResult(
                id, 1000L + id, section, "1", number, gradeId, gradeId == 7L ? "VIP" : "R", 150_000, selectable);
    }

    private static SeatMapSnapshot snapshot(List<SelectableSeatResult> seats) {
        return SeatMapSnapshot.of(new SelectableSeatsResult(501L, 2L, AS_OF, seats));
    }

    private static boolean bit(String bitmap, int i) {
        byte[] bits = Base64.getDecoder().decode(bitmap);
        return ((bits[i >> 3] >> (i & 7)) & 1) == 1;
    }

    @Test
    @DisplayName("좌석 id 순서에서 흩어진 구역도 section 값으로 모으고, 구역 좌석은 id 오름차순이다")
    void groupsScatteredSeatsBySection() {
        // 공연장에 구역을 섞어 등록하면 회차 좌석 id 순서에서 A·B가 번갈아 나온다
        SeatMapSnapshot snapshot = snapshot(List.of(
                seat(1, "A", 1, 7L, true),
                seat(2, "B", 1, 7L, true),
                seat(3, "A", 2, 7L, false),
                seat(4, "B", 2, 7L, true),
                seat(5, "A", 3, 7L, true)));

        SeatMapSnapshot a = snapshot.sections().get("A").orElseThrow();

        assertThat(a.result().seats())
                .extracting(SelectableSeatResult::scheduleSeatId)
                .containsExactly(1L, 3L, 5L);
        assertThat(a.result().scheduleId()).isEqualTo(501L);
        assertThat(a.configVersion()).isEqualTo(2L);
        assertThat(a.result().asOf()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("구역 비트맵의 i번째 비트는 구역 좌석 목록의 i번째 좌석이다")
    void sectionBitmapFollowsSectionSeatOrder() {
        SeatMapSnapshot snapshot = snapshot(List.of(
                seat(1, "A", 1, 7L, true),
                seat(2, "B", 1, 7L, false),
                seat(3, "A", 2, 7L, false),
                seat(4, "B", 2, 7L, true),
                seat(5, "A", 3, 7L, true)));

        SeatMapSnapshot a = snapshot.sections().get("A").orElseThrow();
        SeatMapSnapshot b = snapshot.sections().get("B").orElseThrow();

        assertThat(a.seatCount()).isEqualTo(3);
        assertThat(List.of(bit(a.selectableBitmap(), 0), bit(a.selectableBitmap(), 1), bit(a.selectableBitmap(), 2)))
                .containsExactly(true, false, true);
        assertThat(b.seatCount()).isEqualTo(2);
        assertThat(List.of(bit(b.selectableBitmap(), 0), bit(b.selectableBitmap(), 1)))
                .containsExactly(false, true);
    }

    @Test
    @DisplayName("구역 요약은 구역의 가장 작은 좌석 id 순이고, 좌석 수와 선택 가능 수를 센다. 한 구역에 등급이 섞여도 한 구역이다")
    void summariesInFirstSeatOrder() {
        SeatMapSnapshot snapshot = snapshot(List.of(
                seat(1, "나", 1, 7L, true),
                seat(2, "가", 1, 8L, false),
                seat(3, "나", 2, 8L, false),
                seat(4, "가", 2, 7L, false)));

        assertThat(snapshot.sections().summaries())
                .containsExactly(new SeatSection("나", 2, 1), new SeatSection("가", 2, 0));
    }

    @Test
    @DisplayName("구역 이름은 정확히 일치해야 찾는다: 대소문자·앞뒤 공백이 다르면 없는 구역이다")
    void exactMatchOnly() {
        SeatMapSnapshot snapshot = snapshot(List.of(seat(1, "1층 A구역", 1, 7L, true), seat(2, "b", 1, 7L, true)));

        assertThat(snapshot.sections().get("1층 A구역")).isPresent();
        assertThat(snapshot.sections().get("1층 A구역 ")).isEmpty();
        assertThat(snapshot.sections().get("B")).isEmpty();
        assertThat(snapshot.sections().get("C")).isEmpty();
    }

    @Test
    @DisplayName("구역 하위 스냅샷은 다시 구역을 나누지 않는다")
    void sectionSnapshotHasNoSections() {
        SeatMapSnapshot snapshot = snapshot(List.of(seat(1, "A", 1, 7L, true)));

        SeatMapSnapshot a = snapshot.sections().get("A").orElseThrow();

        assertThat(a.sections().summaries()).isEmpty();
        assertThat(a.sections().get("A")).isEmpty();
    }

    @Test
    @DisplayName("좌석이 없는 회차는 구역도 없다")
    void noSeatsNoSections() {
        SeatMapSnapshot snapshot = snapshot(List.of());

        assertThat(snapshot.sections().summaries()).isEmpty();
    }
}
