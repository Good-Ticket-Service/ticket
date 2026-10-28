package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgInquireRequest;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.pg.FakePgAdmin;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

/** 승인 흐름에서 실제 HTTP 어댑터를 거쳐 모의 PG의 저장 결과와 Payment DB를 대조한다. */
class PaymentConfirmHttpIntegrationTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(6_000_000);

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentTransactions transactions;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realHttpApprovalAndSuccessWithLostResponse(boolean loseResponse) {
        String base = FakePgContainer.baseUrl();
        FakePgAdmin admin = new FakePgAdmin(base);
        admin.clearFaults();
        String order = "http-confirm-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order,
                IDS.incrementAndGet(),
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                50_000,
                5_000,
                LocalDateTime.of(2099, 1, 1, 0, 0)));
        String token = admin.tossCheckout(order, 45_000);
        PgProperties properties = new PgProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                new PgProperties.Toss(base, "test_sk_fake_toss"),
                new PgProperties.Kakao(
                        base,
                        "DEV_fake_kakao",
                        "TC0ONETIME",
                        "http://localhost/approve",
                        "http://localhost/cancel",
                        "http://localhost/fail"));
        TossPgClient http = new TossPgClient(RestClient.builder(), properties, new ObjectMapper());
        pg.approveReturns(http::approve);
        try {
            if (loseResponse) admin.fault("TOSS", "CONFIRM", "{\"responseLossRate\":1}");
            var result = service.confirm(order, new ConfirmCommand(token, 45_000));
            assertThat(admin.transactionStatus(order)).isEqualTo("DONE");
            assertThat(result.status()).isEqualTo(loseResponse ? PaymentStatus.IN_PROGRESS : PaymentStatus.DONE);
            // 복구 잡 자체는 Task 9 범위다. 여기서는 동일한 원거래 조회 결과를 공유 complete에 전달한다.
            admin.clearFaults();
            PgInquiry inquiry = http.inquire(new PgInquireRequest(order, token));
            assertThat(inquiry).isInstanceOf(PgInquiry.Approved.class);
            PgInquiry.Approved approved = (PgInquiry.Approved) inquiry;
            transactions.complete(
                    result.paymentId(),
                    new com.sparta.ticket.payment.domain.model.pg.PgResult.Approved(
                            approved.transactionId(), approved.amount(), approved.approvedAt()));
            assertThat(service.find(order).status()).isEqualTo(PaymentStatus.DONE);
            assertThat(pg.approveCalls()).isEqualTo(1);
        } finally {
            admin.clearFaults();
        }
    }
}
