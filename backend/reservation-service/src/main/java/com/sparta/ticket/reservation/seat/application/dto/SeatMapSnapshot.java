package com.sparta.ticket.reservation.seat.application.dto;

import java.util.Base64;
import java.util.List;

/**
 * 회차 좌석 배치도 캐시에 두는 단위. 기존 조회(GET /api/seats/schedules/{scheduleId})·layout·status가 모두 이 스냅샷 하나에서 응답을
 * 만든다. 그래서 같은 순간의 세 응답은 좌석 순서와 판정이 항상 같다(#271).
 *
 * @param result 판정 결과. 좌석은 id 오름차순이다
 * @param selectableBitmap 선택 가능 비트맵(base64). i번째 비트가 result.seats()의 i번째 좌석이다. 규칙은 {@link #of}
 */
public record SeatMapSnapshot(SelectableSeatsResult result, String selectableBitmap) {

    /**
     * 판정 결과로 스냅샷을 만들면서 비트맵을 한 번 계산한다. 캐시가 회차마다 TTL에 한 번 부르므로 요청마다 다시 만들지 않는다.
     *
     * <p>비트 규칙(docs/api.md): i번째 좌석은 (i / 8)번째 바이트의 (i % 8)번째 비트(가장 낮은 비트부터)이고, 1이면 선택 가능이다.
     * 바이트 길이는 ceil(좌석 수 / 8)이며, 마지막 바이트의 남는 비트는 0이다. 표준 base64(패딩 포함)로 보낸다.
     */
    public static SeatMapSnapshot of(SelectableSeatsResult result) {
        List<SelectableSeatResult> seats = result.seats();
        byte[] bits = new byte[(seats.size() + 7) / 8];
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).selectable()) {
                bits[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return new SeatMapSnapshot(result, Base64.getEncoder().encodeToString(bits));
    }

    public long configVersion() {
        return result.configVersion();
    }

    public int seatCount() {
        return result.seats().size();
    }
}
