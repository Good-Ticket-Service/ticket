package com.sparta.ticket.reservation.seat.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 선택 가능 비트맵 규칙(docs/api.md)을 확인한다: i번째 좌석 = (i/8)번째 바이트의 (i%8)번째 비트, 길이 ceil(n/8), 표준 base64. */
class SeatMapSnapshotTest {

    private static SelectableSeatsResult result(int seatCount, IntPredicate selectable) {
        List<SelectableSeatResult> seats = new ArrayList<>(seatCount);
        for (int i = 0; i < seatCount; i++) {
            seats.add(new SelectableSeatResult(
                    100L + i, 200L + i, "A", "1", i + 1, 7L, "VIP", 150_000, selectable.test(i)));
        }
        return new SelectableSeatsResult(501L, 2L, LocalDateTime.of(2026, 11, 6, 12, 0), seats);
    }

    /** 응답을 받은 클라이언트가 하는 것과 같은 방법으로 i번째 비트를 읽는다. */
    private static boolean bit(byte[] bits, int i) {
        return ((bits[i >> 3] >> (i & 7)) & 1) == 1;
    }

    @Test
    @DisplayName("i번째 비트가 i번째 좌석의 선택 가능 여부이고, 바이트 안에서는 가장 낮은 비트부터 채운다")
    void bitOrder() {
        // 좌석 0, 2, 9만 선택 가능 → 첫 바이트 0b00000101, 둘째 바이트 0b00000010
        SeatMapSnapshot snapshot = SeatMapSnapshot.of(result(10, i -> i == 0 || i == 2 || i == 9));

        byte[] bits = Base64.getDecoder().decode(snapshot.selectableBitmap());

        assertThat(bits).containsExactly(0b0000_0101, 0b0000_0010);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 3000, 20_001})
    @DisplayName("길이는 ceil(좌석 수 / 8)이고, 모든 좌석의 비트가 판정과 같으며 남는 비트는 0이다")
    void lengthAndEveryBit(int seatCount) {
        IntPredicate selectable = i -> i % 3 != 1;
        SeatMapSnapshot snapshot = SeatMapSnapshot.of(result(seatCount, selectable));

        byte[] bits = Base64.getDecoder().decode(snapshot.selectableBitmap());

        assertThat(bits).hasSize((seatCount + 7) / 8);
        for (int i = 0; i < seatCount; i++) {
            assertThat(bit(bits, i)).as("좌석 %d", i).isEqualTo(selectable.test(i));
        }
        for (int i = seatCount; i < bits.length * 8; i++) {
            assertThat(bit(bits, i)).as("남는 비트 %d", i).isFalse();
        }
        assertThat(snapshot.seatCount()).isEqualTo(seatCount);
        assertThat(snapshot.configVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("모두 선택 가능하면 꽉 찬 바이트는 0xFF, 모두 불가면 모든 바이트가 0이다")
    void allOrNothing() {
        byte[] all = Base64.getDecoder()
                .decode(SeatMapSnapshot.of(result(16, i -> true)).selectableBitmap());
        byte[] none = Base64.getDecoder()
                .decode(SeatMapSnapshot.of(result(16, i -> false)).selectableBitmap());

        assertThat(all).containsExactly((byte) 0xFF, (byte) 0xFF);
        assertThat(none).containsExactly(0, 0);
    }

    @Test
    @DisplayName("표준 base64(패딩 포함)로 보낸다. 3000석은 375바이트, 500자다")
    void standardBase64() {
        String encoded = SeatMapSnapshot.of(result(3000, i -> true)).selectableBitmap();

        assertThat(encoded).hasSize(500).matches("[A-Za-z0-9+/]+={0,2}");
    }
}
