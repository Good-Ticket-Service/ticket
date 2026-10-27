package com.sparta.ticket.payment.infrastructure.pg.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgInquireRequest;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgReadyRequest;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.pg.PgClientContractTest;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoPgClientContractTest extends PgClientContractTest {

    @Override
    protected String provider() {
        return "KAKAO";
    }

    @Override
    protected PgClient newClient(PgProperties properties) {
        return new KakaoPgClient(restClientBuilder(), properties, objectMapper());
    }

    /** 카카오는 준비에서 tid를 받고, 결제창 인증(사용자 승인)으로 pg_token을 받는다. */
    @Override
    protected Authorized authorize(String orderId) {
        PgReadyResult ready = client.ready(new PgReadyRequest(orderId, USER_ID, AMOUNT, "공연 티켓"));
        assertThat(ready).isInstanceOf(PgReadyResult.Ready.class);
        PgReadyResult.Ready readyResult = (PgReadyResult.Ready) ready;
        assertThat(readyResult.checkoutUrl()).isNotBlank();
        return new Authorized(readyResult.transactionId(), admin.kakaoUserApprove(readyResult.transactionId()));
    }

    @Test
    @DisplayName("준비 장애(5xx·응답 유실)는 거절·불명을 나누지 않고 Failed다")
    void readyFailure() {
        admin.fault("KAKAO", "READY", "{\"serverErrorRate\":1}");
        assertThat(client.ready(new PgReadyRequest(orderId, USER_ID, AMOUNT, "공연 티켓")))
                .isInstanceOf(PgReadyResult.Failed.class);

        admin.fault("KAKAO", "READY", "{\"responseLossRate\":1,\"holdMs\":3000}");
        assertThat(client.ready(new PgReadyRequest("other-" + orderId, USER_ID, AMOUNT, "공연 티켓")))
                .isInstanceOf(PgReadyResult.Failed.class);
    }

    @Test
    @DisplayName("tid가 없거나 PG에 없는 tid를 조회하면 NotFound다")
    void inquireWithoutTid() {
        assertThat(client.inquire(new PgInquireRequest(orderId, null))).isInstanceOf(PgInquiry.NotFound.class);
        assertThat(client.inquire(new PgInquireRequest(orderId, "T-unknown"))).isInstanceOf(PgInquiry.NotFound.class);
    }

    @Test
    @DisplayName("사용자 승인까지 끝나고 승인 전인 결제를 조회하면 NotFound다")
    void inquireBeforeApproval() {
        Authorized authorized = authorize(orderId);

        assertThat(inquire(authorized)).isInstanceOf(PgInquiry.NotFound.class);
    }

    @Test
    @DisplayName("이미 승인된 결제를 다시 승인하면 멱등 키가 없어 이미 처리됨이 오고, 앞선 승인이 성공했을 수 있어 Unknown이다")
    void approveTwiceIsUnknown() {
        Authorized authorized = authorize(orderId);
        assertThat(approve(authorized)).isInstanceOf(PgResult.Approved.class);

        assertThat(approve(authorized)).isInstanceOf(PgResult.Unknown.class);
    }
}
