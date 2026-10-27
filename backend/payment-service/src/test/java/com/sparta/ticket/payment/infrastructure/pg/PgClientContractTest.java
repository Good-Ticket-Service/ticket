package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgApproveRequest;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgCancelRequest;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgInquireRequest;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 두 PG 어댑터가 같은 계약(docs/payment/plan.md 6절 분류표)을 지키는지 fake PG로 확인한다. 하위 클래스는 PG별 준비·사용자 인증
 * 단계만 정한다. 장애는 fake PG 관리 API로 걸고, 읽기 타임아웃은 짧게(1초) 줄여 응답 유실을 Unknown으로 만든다.
 */
public abstract class PgClientContractTest {

    protected static final Long USER_ID = 1L;
    protected static final int AMOUNT = 30_000;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    protected final FakePgAdmin admin = new FakePgAdmin(FakePgContainer.baseUrl());
    protected PgClient client;
    protected String orderId;

    /** 결제 승인에 필요한 PG 거래 ID와 사용자 인증값. */
    public record Authorized(String transactionId, String authToken) {}

    protected abstract String provider();

    protected abstract PgClient newClient(PgProperties properties);

    /** 준비 → 결제창 대체(사용자 인증)까지 마친다. */
    protected abstract Authorized authorize(String orderId);

    protected static PgProperties properties(String baseUrl) {
        return new PgProperties(
                Duration.ofSeconds(2),
                READ_TIMEOUT,
                new PgProperties.Toss(baseUrl, "test_sk_fake_toss"),
                new PgProperties.Kakao(
                        baseUrl,
                        "DEV_fake_kakao",
                        "TC0ONETIME",
                        "http://localhost/approve",
                        "http://localhost/cancel",
                        "http://localhost/fail"));
    }

    @BeforeEach
    void setUp() {
        admin.clearFaults();
        client = newClient(properties(FakePgContainer.baseUrl()));
        orderId = "order-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        admin.clearFaults();
    }

    protected PgResult approve(Authorized authorized) {
        return client.approve(
                new PgApproveRequest(orderId, authorized.transactionId(), authorized.authToken(), AMOUNT, USER_ID));
    }

    protected PgInquiry inquire(Authorized authorized) {
        return client.inquire(new PgInquireRequest(orderId, authorized.transactionId()));
    }

    protected PgResult cancel(Authorized authorized, String idempotencyKey) {
        return client.cancel(new PgCancelRequest(orderId, authorized.transactionId(), AMOUNT, idempotencyKey, "예매 취소"));
    }

    protected PgClient clientFor(String baseUrl) {
        return newClient(properties(baseUrl));
    }

    protected static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    protected static RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Test
    @DisplayName("준비 → 사용자 인증 → 승인 → 조회 → 취소 → 조회가 끝까지 된다")
    void fullFlow() {
        Authorized authorized = authorize(orderId);

        PgResult approved = approve(authorized);
        assertThat(approved).isInstanceOf(PgResult.Approved.class);
        assertThat(((PgResult.Approved) approved).amount()).isEqualTo(AMOUNT);
        assertThat(((PgResult.Approved) approved).at()).isNotNull();

        PgInquiry inquiry = inquire(authorized);
        assertThat(inquiry).isInstanceOf(PgInquiry.Approved.class);
        assertThat(((PgInquiry.Approved) inquiry).amount()).isEqualTo(AMOUNT);

        assertThat(cancel(authorized, orderId + ":cancel:0")).isInstanceOf(PgResult.Approved.class);
        assertThat(inquire(authorized)).isInstanceOf(PgInquiry.Canceled.class);
    }

    @Test
    @DisplayName("승인 거절은 PG 원본 코드를 담은 Rejected다")
    void rejection() {
        Authorized authorized = authorize(orderId);
        admin.fault(provider(), "CONFIRM", "{\"failRate\":1}");

        PgResult result = approve(authorized);

        assertThat(result).isInstanceOf(PgResult.Rejected.class);
        assertThat(((PgResult.Rejected) result).code()).isNotIn("PG_UNAVAILABLE", "PG_BUSY");
    }

    @Test
    @DisplayName("5xx는 Unknown이고, 처리하지 않은 5xx라 조회하면 승인 기록이 없다")
    void serverError() {
        Authorized authorized = authorize(orderId);
        admin.fault(provider(), "CONFIRM", "{\"serverErrorRate\":1}");

        assertThat(approve(authorized)).isInstanceOf(PgResult.Unknown.class);
        admin.clearFaults();
        assertThat(inquire(authorized)).isInstanceOf(PgInquiry.NotFound.class);
    }

    @Test
    @DisplayName("응답 유실(읽기 타임아웃)은 Rejected가 아니라 Unknown이고, 조회하면 승인이 확인된다")
    void readTimeoutIsUnknown() {
        Authorized authorized = authorize(orderId);
        admin.fault(provider(), "CONFIRM", "{\"responseLossRate\":1,\"holdMs\":3000}");

        PgResult result = approve(authorized);

        assertThat(result).isInstanceOf(PgResult.Unknown.class);
        admin.clearFaults();
        assertThat(inquire(authorized)).isInstanceOf(PgInquiry.Approved.class);
    }

    @Test
    @DisplayName("지연 승인 직후 조회하면 아직 승인 기록이 없다(NotFound)")
    void delayedApprovalIsNotFoundYet() {
        Authorized authorized = authorize(orderId);
        admin.fault(provider(), "CONFIRM", "{\"delayedApprovalRate\":1,\"delayedApprovalMs\":5000,\"holdMs\":1500}");

        assertThat(approve(authorized)).isInstanceOf(PgResult.Unknown.class);
        admin.clearFaults();
        assertThat(inquire(authorized)).isInstanceOf(PgInquiry.NotFound.class);
    }

    @Test
    @DisplayName("PG 처리 한도 초과(429)는 Rejected(PG_BUSY)다")
    void rateLimitIsBusy() {
        Authorized authorized = authorize(orderId);
        admin.fault(provider(), "CONFIRM", "{\"rateLimitPerSecond\":1}");

        boolean busy = false;
        for (int i = 0; i < 3 && !busy; i++) {
            busy = approve(authorized) instanceof PgResult.Rejected rejected
                    && rejected.code().equals("PG_BUSY");
        }

        assertThat(busy).isTrue();
    }

    @Test
    @DisplayName("연결 거부는 Rejected(PG_UNAVAILABLE), 조회는 Unknown이다")
    void connectionRefused() {
        Authorized authorized = authorize(orderId);
        PgClient unreachable = clientFor("http://localhost:1");

        PgResult result = unreachable.approve(
                new PgApproveRequest(orderId, authorized.transactionId(), authorized.authToken(), AMOUNT, USER_ID));

        assertThat(result).isInstanceOf(PgResult.Rejected.class);
        assertThat(((PgResult.Rejected) result).code()).isEqualTo("PG_UNAVAILABLE");
        assertThat(unreachable.inquire(new PgInquireRequest(orderId, authorized.transactionId())))
                .isInstanceOf(PgInquiry.Unknown.class);
    }

    @Test
    @DisplayName("같은 결제에 취소를 두 번 불러도 둘 다 성공이고 PG 거래는 한 번 취소된다")
    void cancelTwice() {
        Authorized authorized = authorize(orderId);
        approve(authorized);

        assertThat(cancel(authorized, orderId + ":cancel:0")).isInstanceOf(PgResult.Approved.class);
        assertThat(cancel(authorized, orderId + ":cancel:0")).isInstanceOf(PgResult.Approved.class);
        assertThat(cancel(authorized, orderId + ":cancel:1")).isInstanceOf(PgResult.Approved.class);

        assertThat(admin.transactionStatus(orderId)).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("취소 응답을 잃으면 Unknown이고, 다시 부르면 이미 끝난 취소를 성공으로 확인한다")
    void cancelResponseLoss() {
        Authorized authorized = authorize(orderId);
        approve(authorized);
        admin.fault(provider(), "CANCEL", "{\"responseLossRate\":1,\"holdMs\":3000}");

        assertThat(cancel(authorized, orderId + ":cancel:0")).isInstanceOf(PgResult.Unknown.class);
        admin.clearFaults();

        assertThat(cancel(authorized, orderId + ":cancel:0")).isInstanceOf(PgResult.Approved.class);
        assertThat(admin.transactionStatus(orderId)).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("승인 전 결제의 취소는 업무 거절(Rejected)이다")
    void cancelBeforeApproval() {
        Authorized authorized = authorize(orderId);

        PgResult result = cancel(authorized, orderId + ":cancel:0");

        assertThat(result).isInstanceOf(PgResult.Rejected.class);
        assertThat(((PgResult.Rejected) result).code()).isNotIn("PG_UNAVAILABLE", "PG_BUSY");
    }
}
