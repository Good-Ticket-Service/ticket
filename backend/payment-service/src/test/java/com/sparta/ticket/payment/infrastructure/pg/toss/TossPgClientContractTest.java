package com.sparta.ticket.payment.infrastructure.pg.toss;

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

class TossPgClientContractTest extends PgClientContractTest {

    @Override
    protected String provider() {
        return "TOSS";
    }

    @Override
    protected PgClient newClient(PgProperties properties) {
        return new TossPgClient(restClientBuilder(), properties, objectMapper());
    }

    /** 토스 준비는 PG를 부르지 않는다. 결제창 인증으로 받은 paymentKey가 거래 ID이자 인증값이다. */
    @Override
    protected Authorized authorize(String orderId) {
        assertThat(client.ready(new PgReadyRequest(orderId, USER_ID, AMOUNT, "공연 티켓")))
                .isEqualTo(new PgReadyResult.Ready(null, null));
        String paymentKey = admin.tossCheckout(orderId, AMOUNT);
        return new Authorized(paymentKey, paymentKey);
    }

    @Test
    @DisplayName("결제창 인증 기록이 없는 주문을 조회하면 NotFound다")
    void inquireUnknownOrder() {
        assertThat(client.inquire(new PgInquireRequest("none-" + orderId, null)))
                .isInstanceOf(PgInquiry.NotFound.class);
    }

    @Test
    @DisplayName("결제창 인증만 끝난 결제를 조회하면 승인 전이라 NotFound다")
    void inquireBeforeApproval() {
        Authorized authorized = authorize(orderId);

        assertThat(inquire(authorized)).isInstanceOf(PgInquiry.NotFound.class);
    }

    @Test
    @DisplayName("같은 결제를 다시 승인하면 같은 멱등 키라 처음 성공 응답을 재현한다")
    void approveTwiceReproducesFirstResult() {
        Authorized authorized = authorize(orderId);
        PgResult first = approve(authorized);

        assertThat(approve(authorized)).isEqualTo(first);
    }
}
