package com.sparta.ticket.reservation.seat.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Accept-Encoding에서 gzip을 받을 수 있는지 판단한다(#287 사전 압축). */
class AcceptEncodingTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "gzip",
                "GZIP",
                "gzip, deflate, br",
                "br;q=1.0, gzip;q=0.8, *;q=0.1",
                "x-gzip",
                "*",
                "identity, *;q=0.5",
                "gzip;q=0, gzip;q=0.5"
            })
    @DisplayName("gzip(또는 *)이 q>0으로 있으면 받는다")
    void accepts(String header) {
        assertThat(AcceptEncoding.acceptsGzip(header)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "identity",
                "br, deflate",
                "gzip;q=0",
                "gzip;q=0.0, br",
                "*;q=0",
                "gzip;q=0, *",
                "gzip;q=abc",
                " "
            })
    @DisplayName("헤더가 없거나, gzip이 없거나, q=0이면 받지 않는다")
    void rejects(String header) {
        assertThat(AcceptEncoding.acceptsGzip(header)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"gzip; q=0.5|true", "gzip ;Q=1|true", "gzip;level=1|true"})
    @DisplayName("q 앞뒤 공백과 대문자 Q, q가 아닌 매개변수를 읽는다")
    void parameters(String header, boolean expected) {
        assertThat(AcceptEncoding.acceptsGzip(header)).isEqualTo(expected);
    }
}
