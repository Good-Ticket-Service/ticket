package com.sparta.ticket.reservation.seat.application.dto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * layout 응답 본문 한 벌. 원본(identity)과 미리 압축한 gzip을 함께 둔다(#287 사전 압축). (회차, configVersion)마다 한 번 만든다.
 * 처음에는 원본만 만들어 바로 주고({@link #identityOnly}), 압축본은 캐시가 뒤에서 만들어 바꿔 넣는다({@link #compressed}).
 *
 * @param identity 직렬화한 원본 바이트
 * @param gzip 원본을 gzip으로 압축한 바이트. 아직 압축하지 않았거나 원본이 {@link #MIN_GZIP_BYTES}보다 작으면 null이고, 그때는 원본을 보낸다
 */
public record SeatLayoutBody(byte[] identity, byte[] gzip) {

    /**
     * 이보다 작은 원본은 압축하지 않는다. gzip은 머리말 등 고정 바이트가 붙어 아주 작은 본문은 이득이 없다.
     * Tomcat server.compression.min-response-size 기본값(2KB)과 같다. 실제 공연장 layout은 좌석 약 13개부터 이 값을 넘는다.
     */
    public static final int MIN_GZIP_BYTES = 2048;

    /** 원본만 있는 한 벌. 처음 요청은 압축을 기다리지 않고 이것을 받는다. */
    public static SeatLayoutBody identityOnly(byte[] identity) {
        return new SeatLayoutBody(identity, null);
    }

    /** 압축본을 아직 만들지 않았고, 압축할 만큼 큰지. */
    public boolean needsGzip() {
        return gzip == null && identity.length >= MIN_GZIP_BYTES;
    }

    /**
     * 압축본을 더한 한 벌. 압축할 필요가 없으면 자신을 돌려준다. 압축은 버전마다 한 번이고 요청이 기다리지 않으므로, 시간이 조금 더 들어도
     * 전송량이 가장 작은 BEST_COMPRESSION으로 한다(20,000석 약 50ms). 받는 쪽이 푸는 비용은 압축 수준과 거의 상관없다.
     */
    public SeatLayoutBody compressed() {
        return needsGzip() ? new SeatLayoutBody(identity, gzip(identity)) : this;
    }

    /** 캐시 상한을 계산할 때 쓰는 크기. 원본과 압축본을 모두 메모리에 둔다. */
    public int weight() {
        return identity.length + (gzip == null ? 0 : gzip.length);
    }

    private static byte[] gzip(byte[] source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, source.length / 8));
        try (GZIPOutputStream gz = new BestCompressionGzip(out)) {
            gz.write(source);
        } catch (IOException e) {
            // 메모리 스트림이라 실제로는 나지 않는다
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** GZIPOutputStream은 압축 수준을 생성자로 받지 않아 protected def로 바꾼다. */
    private static final class BestCompressionGzip extends GZIPOutputStream {
        BestCompressionGzip(ByteArrayOutputStream out) throws IOException {
            super(out);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }
}
