package com.sparta.ticket.payment.support;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.support.pg.PgClientFixture;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
public abstract class PaymentIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    protected PgClientFixture pg;

    @BeforeEach
    void resetPg() {
        pg.reset();
    }

    @TestConfiguration
    static class PgFixtureConfig {

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
}
