package com.sparta.ticket.reservation.seat.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** layout 본문 한 벌(원본 + 미리 압축한 gzip)을 확인한다(#287 사전 압축). */
class SeatLayoutBodyTest {

    /** layout과 비슷하게 같은 필드 이름이 반복되는 JSON. */
    private static byte[] layoutLike(int seats) {
        StringBuilder json = new StringBuilder("{\"status\":200,\"message\":\"SUCCESS\",\"data\":{\"seats\":[");
        for (int i = 0; i < seats; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"scheduleSeatId\":")
                    .append(60_000 + i)
                    .append(",\"section\":\"A\",\"rowLabel\":\"")
                    .append(i / 25 + 1)
                    .append("\",\"seatNumber\":")
                    .append(i % 25 + 1)
                    .append(",\"gradeName\":\"VIP\",\"basePrice\":150000}");
        }
        return json.append("]}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gunzip(byte[] gzip) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("2KB 이상이면 gzip을 함께 만들고, 풀면 원본과 같으며 원본보다 작다")
    void compressesLargeBody() throws IOException {
        byte[] identity = layoutLike(1_000);

        SeatLayoutBody body = SeatLayoutBody.identityOnly(identity).compressed();

        assertThat(body.identity()).isSameAs(identity);
        assertThat(body.gzip()).isNotNull();
        assertThat(gunzip(body.gzip())).isEqualTo(identity);
        assertThat(body.gzip().length).isLessThan(identity.length / 5);
    }

    @Test
    @DisplayName("2KB보다 작으면 압축하지 않는다")
    void skipsSmallBody() {
        byte[] small = new byte[SeatLayoutBody.MIN_GZIP_BYTES - 1];
        byte[] threshold = new byte[SeatLayoutBody.MIN_GZIP_BYTES];

        assertThat(SeatLayoutBody.identityOnly(small).needsGzip()).isFalse();
        assertThat(SeatLayoutBody.identityOnly(small).compressed().gzip()).isNull();
        assertThat(SeatLayoutBody.identityOnly(threshold).needsGzip()).isTrue();
        assertThat(SeatLayoutBody.identityOnly(threshold).compressed().gzip()).isNotNull();
    }

    @Test
    @DisplayName("처음에는 원본만 있고, 압축한 뒤에는 더 압축할 필요가 없다")
    void identityFirstThenCompressed() {
        SeatLayoutBody first = SeatLayoutBody.identityOnly(layoutLike(1_000));
        assertThat(first.gzip()).isNull();
        assertThat(first.needsGzip()).isTrue();

        SeatLayoutBody compressed = first.compressed();
        assertThat(compressed.identity()).isSameAs(first.identity());
        assertThat(compressed.needsGzip()).isFalse();
        assertThat(compressed.compressed()).isSameAs(compressed);
    }

    @Test
    @DisplayName("캐시 무게는 원본과 압축본의 합이고, 압축본이 없으면 원본만이다")
    void weight() {
        byte[] identity = layoutLike(1_000);
        SeatLayoutBody body = SeatLayoutBody.identityOnly(identity).compressed();

        assertThat(body.weight()).isEqualTo(identity.length + body.gzip().length);
        assertThat(SeatLayoutBody.identityOnly(new byte[10]).weight()).isEqualTo(10);
    }
}
