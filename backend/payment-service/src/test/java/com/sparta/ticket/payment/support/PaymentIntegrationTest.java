package com.sparta.ticket.payment.support;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.payment.application.port.payment.PaymentResultPublisher;
import com.sparta.ticket.payment.application.port.pg.PgApprovalGate;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.service.payment.SettlementJobWorker;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.infrastructure.persistence.payment.PaymentResultOutboxJdbc;
import com.sparta.ticket.payment.support.pg.PgClientFixture;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * payment-service 통합 테스트의 공통 부모. 모든 통합 테스트가 이 클래스를 상속해 같은 Spring 컨텍스트를 쓴다.
 *
 * Note: schema.sql은 컨텍스트가 뜰 때마다 실행되는데 CREATE TABLE이 IF NOT EXISTS가 아니다. 테스트 클래스마다 설정이 다르면
 * 컨텍스트가 따로 떠서 두 번째 실행이 "이미 있음"으로 실패하므로, 설정을 이 클래스 하나에 모은다.
 * PG는 실제 어댑터 대신 {@link PgClientFixture}로 바꾼다. 실제 HTTP 동작은 어댑터 계약 테스트가 검증한다.
 * open-in-view는 운영과 같이 끈다. 켜 두면 요청 내내 DB 연결을 잡아 "PG 호출은 트랜잭션 밖" 조건이 운영과 달라진다.
 */
@AutoConfigureMockMvc
@Import(PaymentIntegrationTest.PgFixtureConfig.class)
@TestPropertySource(
        properties = {
            "spring.jpa.open-in-view=false",
            "payment.recovery.enabled=false",
            "payment.reconcile.enabled=false",
            "payment.recovery.refund.enabled=false",
            "payment.observation.enabled=false",
            "payment.messaging.result-relay.enabled=false",
            // Note: PG 정산은 기본 활성화한다. 시험에서는 공유 DB의 자동 수집을 끄고 Job을 직접 실행한다.
            "payment.pg.enabled=false",
            // 정산 반영 워커는 스케줄러를 끄고, 시험이 결제·환불을 확정한 뒤 settlementJobs.drain()으로 직접 돌린다(#304).
            "payment.settlement.worker.enabled=false",
            "payment.settlement.posting.transport=DB",
            "payment.settlement.posting.kafka.enabled=false",
            "payment.settlement.consumer-recovery.replay-enabled=false",
            "payment.settlement.consumer-recovery.observation-enabled=false",
            "payment.settlement.projection.mode=D",
            "payment.settlement.projection.auto-start=false",
            // 운영 기본값은 정산 잡을 모두 켠다. 시험에서는 스케줄러가 공유 DB의 정산서·지급을 건드리지 않도록 끄고,
            // 각 시험이 잡·트랜잭션을 직접 부른다.
            "payment.settlement.backfill.enabled=false",
            "payment.settlement.statement.enabled=false",
            "payment.settlement.payout.enabled=false",
            "payment.settlement.payout.recovery.enabled=false",
            "payment.settlement.reconcile.enabled=false"
        })
public abstract class PaymentIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    protected PgClientFixture pg;

    @Autowired
    protected PublisherFixture publisher;

    @Autowired
    protected MutablePaymentClock paymentClock;

    @Autowired
    protected SettlementJobDrain settlementJobs;

    @Autowired
    private JdbcTemplate fixtureJdbc;

    @BeforeEach
    void resetPg() {
        // Note: 일부 시험은 결제 ID 시퀀스를 초기화한다. FK 없는 최초 결과도 함께 비워 시험 간 ID 충돌을 막는다.
        fixtureJdbc.update("DELETE FROM p_payment_result_outbox");
        // Note: 공유 시험 DB의 파생 기준이 다음 시험의 원장·실행 기록을 참조하지 않게 초기화한다.
        fixtureJdbc.update("UPDATE p_reconcile_checkpoint SET valid=false,run_id=NULL,snapshot=NULL,accepted_at=NULL");
        fixtureJdbc.update("DELETE FROM p_reconcile_account_balances");
        pg.reset();
        publisher.fail = false;
        paymentClock.reset();
    }

    @TestConfiguration
    static class PgFixtureConfig {

        @Bean
        @Primary
        MutablePaymentClock fixturePaymentClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        PgApprovalGate fixtureApprovalGate(PgClientFixture fixture) {
            return provider -> fixture.approvalAllowed();
        }

        @Bean
        @Primary
        PublisherFixture fixturePublisher(PaymentResultOutboxJdbc delegate) {
            return new PublisherFixture(delegate);
        }

        @Bean
        SettlementJobDrain settlementJobDrain(
                SettlementJobWorker worker, JdbcTemplate jdbc, MutablePaymentClock clock) {
            return new SettlementJobDrain(worker, jdbc, clock);
        }

        @Bean
        PgClientFixture pgClientFixture() {
            return new PgClientFixture();
        }

        /** 모든 PG를 픽스처 하나로 돌린다. 토스는 PG 준비가 없어 결제 흐름이 준비를 부르지 않는다. */
        @Bean
        @Primary
        PgClientRegistry fixturePgClientRegistry(ObjectProvider<PgClient> clients, PgClientFixture fixture) {
            return new PgClientRegistry(clients) {
                @Override
                public PgClient get(PgProvider provider) {
                    return fixture.client();
                }
            };
        }
    }

    /** 결과 저장 중 실패를 주입해 결제와 환불 의도의 원자적 롤백을 검증한다. */
    public static class PublisherFixture implements PaymentResultPublisher {
        public volatile boolean fail;
        private final PaymentResultOutboxJdbc delegate;

        public PublisherFixture(PaymentResultOutboxJdbc delegate) {
            this.delegate = delegate;
        }

        @Override
        public void paymentFinalized(Payment payment) {
            if (fail) {
                throw new IllegalStateException("결과 저장 실패 주입");
            }
            delegate.paymentFinalized(payment);
        }

        @Override
        public void refundFinalized(Payment payment, Refund refund) {
            if (fail) throw new IllegalStateException("저장 실패 주입");
        }
    }
}
