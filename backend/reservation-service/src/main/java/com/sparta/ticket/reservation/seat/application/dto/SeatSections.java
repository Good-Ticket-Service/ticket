package com.sparta.ticket.reservation.seat.application.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 회차 스냅샷을 구역(좌석의 section 값)별로 나눈 것(#397). 구역 목록 응답과 구역 단위 layout·status가 이것을 쓴다.
 * 스냅샷을 만들 때 한 번 계산하므로, 같은 스냅샷에서 나온 구역 목록·구역 layout·구역 status는 좌석과 판정이 서로 맞다.
 *
 * <p>구역은 별도 엔터티가 없고 section 문자열이 곧 구역이다. 공연 서비스가 정규화하지 않으므로 정확히 일치하는 값으로만 찾는다.
 * 구역 좌석은 회차 좌석 id 순서에서 흩어질 수 있어(공연장 좌석 등록 순서를 따름) id 범위가 아니라 section 값으로 모은다.
 * 구역 순서는 회차 좌석을 id 오름차순으로 볼 때 처음 나온 순서, 즉 구역의 가장 작은 좌석 id 순이다.
 */
public final class SeatSections {

    /** 구역을 나누지 않은 상태. 구역 하위 스냅샷이 다시 구역을 갖지 않게 할 때 쓴다. */
    static final SeatSections NONE = new SeatSections(List.of(), Map.of());

    private final List<SeatSection> summaries;
    private final Map<String, SeatMapSnapshot> snapshots;

    private SeatSections(List<SeatSection> summaries, Map<String, SeatMapSnapshot> snapshots) {
        this.summaries = summaries;
        this.snapshots = snapshots;
    }

    /** 판정 결과(좌석 id 오름차순)를 구역별로 나누고, 구역마다 하위 스냅샷과 비트맵을 만든다. */
    static SeatSections of(SelectableSeatsResult result) {
        Map<String, List<SelectableSeatResult>> grouped = new LinkedHashMap<>();
        for (SelectableSeatResult seat : result.seats()) {
            grouped.computeIfAbsent(seat.section(), ignored -> new ArrayList<>())
                    .add(seat);
        }
        List<SeatSection> summaries = new ArrayList<>(grouped.size());
        Map<String, SeatMapSnapshot> snapshots = new LinkedHashMap<>(grouped.size() * 2);
        grouped.forEach((section, seats) -> {
            SelectableSeatsResult sub = new SelectableSeatsResult(
                    result.scheduleId(), result.configVersion(), result.asOf(), List.copyOf(seats));
            snapshots.put(section, new SeatMapSnapshot(sub, SeatMapSnapshot.bitmap(seats), NONE));
            summaries.add(new SeatSection(section, seats.size(), (int)
                    seats.stream().filter(SelectableSeatResult::selectable).count()));
        });
        return new SeatSections(List.copyOf(summaries), Map.copyOf(snapshots));
    }

    /** 구역 요약. 순서는 구역의 가장 작은 좌석 id 순이다. */
    public List<SeatSection> summaries() {
        return summaries;
    }

    /** 그 구역 좌석만 담은 스냅샷. 같은 configVersion·asOf이고 좌석은 id 오름차순, 비트맵도 이 좌석 순서다. 없는 구역이면 비어 있다. */
    public Optional<SeatMapSnapshot> get(String section) {
        return Optional.ofNullable(snapshots.get(section));
    }

    /** 구역 하나의 요약. selectableCount는 같은 스냅샷의 status 판정으로 센다. */
    public record SeatSection(String section, int seatCount, int selectableCount) {}
}
