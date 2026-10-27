package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** 전송 실패를 "보내기 전"과 "보낸 뒤"로 나누는 규칙. 이 구분이 Rejected와 Unknown을 가른다. */
class PgHttpTest {

    private static ResourceAccessException failure(Throwable cause) {
        return new ResourceAccessException("I/O error", cause instanceof IOException io ? io : new IOException(cause));
    }

    @Test
    @DisplayName("연결 거부·연결 타임아웃은 요청을 보내지 못한 것이다")
    void notSent() {
        assertThat(PgHttp.classify(failure(new ConnectException("Connection refused"))))
                .isInstanceOf(PgCall.NotSent.class);
        assertThat(PgHttp.classify(failure(new HttpConnectTimeoutException("connect timed out"))))
                .isInstanceOf(PgCall.NotSent.class);
    }

    @Test
    @DisplayName("읽기 타임아웃과 그 밖의 I/O 오류는 보냈을 수 있는 실패다")
    void sentButFailed() {
        assertThat(PgHttp.classify(failure(new HttpTimeoutException("request timed out"))))
                .isInstanceOf(PgCall.SentButFailed.class);
        assertThat(PgHttp.classify(failure(new IOException("Connection reset"))))
                .isInstanceOf(PgCall.SentButFailed.class);
    }

    @Test
    @DisplayName("공통 분류: 보내기 전 실패는 PG_UNAVAILABLE, 429는 PG_BUSY, 보낸 뒤 실패와 5xx는 Unknown")
    void transportResult() {
        assertThat(PgHttp.transportResult(new PgCall.NotSent("refused")))
                .get()
                .isEqualTo(new PgResult.Rejected("PG_UNAVAILABLE", "refused"));
        assertThat(PgHttp.transportResult(new PgCall.Responded(429, "")))
                .get()
                .isInstanceOf(PgResult.Rejected.class)
                .extracting("code")
                .isEqualTo("PG_BUSY");
        assertThat(PgHttp.transportResult(new PgCall.SentButFailed("timeout")))
                .get()
                .isInstanceOf(PgResult.Unknown.class);
        assertThat(PgHttp.transportResult(new PgCall.Responded(500, ""))).get().isInstanceOf(PgResult.Unknown.class);
        assertThat(PgHttp.transportResult(new PgCall.Responded(200, "{}"))).isEmpty();
        assertThat(PgHttp.transportResult(new PgCall.Responded(400, "{}"))).isEmpty();
    }
}
