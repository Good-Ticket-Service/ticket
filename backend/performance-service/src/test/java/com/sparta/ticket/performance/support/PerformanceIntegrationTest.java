package com.sparta.ticket.performance.support;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * performance-service 통합 테스트의 공통 부모. 모든 통합 테스트가 이 클래스를 상속해 같은 Spring 컨텍스트를 쓴다.
 *
 * Note: schema.sql은 컨텍스트가 뜰 때마다 실행되는데 CREATE TABLE이 IF NOT EXISTS가 아니다. 테스트 클래스마다 설정이 다르면
 * 컨텍스트가 따로 떠서 두 번째 실행이 "이미 있음"으로 실패하므로, 설정을 이 클래스 하나에 모은다. 하위 클래스는 @Import 등을 붙이지 않는다.
 * open-in-view는 운영(config-repo/application.yml)과 같이 끈다.
 */
@AutoConfigureMockMvc
@Import(PerformanceIntegrationTest.ClockTestConfig.class)
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
public abstract class PerformanceIntegrationTest extends PostgresIntegrationTest {

    /** 테스트 데이터(sql/public-performance-fixture.sql)의 기준 시각. 매 테스트 전에 이 시각으로 되돌린다. */
    protected static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }
}
