package com.sparta.ticket.performance.support;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.performance.application.port.PublicPerformanceReader;
import java.sql.SQLException;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * performance-service 통합 테스트의 공통 부모. 모든 통합 테스트가 이 클래스를 상속해 같은 Spring 컨텍스트를 쓴다.
 *
 * Note: schema.sql은 컨텍스트가 뜰 때마다 실행되는데 CREATE TABLE이 IF NOT EXISTS가 아니다. 테스트 클래스마다 설정이 다르면
 * 컨텍스트가 따로 떠서 두 번째 실행이 "이미 있음"으로 실패하므로, 설정을 이 클래스 하나에 모은다. 하위 클래스는 @Import 등을 붙이지 않는다.
 * open-in-view는 운영(config-repo/application.yml)과 같이 끈다. 좌석 준비는 가짜 구현체로 받고, 재시도 스케줄러는 끄고 테스트가
 * 직접 부른다(시각을 MutableClock으로 정하기 위해서다).
 * 공개 공연 상세 캐시(이슈 #398)용 Redis도 여기서 한 번만 띄운다. 테스트마다 fixture가 같은 ID로 다시 들어가므로,
 * 앞 테스트가 남긴 캐시 값을 읽지 않게 매 테스트 전에 비운다.
 */
@AutoConfigureMockMvc
@Import(PerformanceIntegrationTest.ClockTestConfig.class)
@TestPropertySource(
        properties = {
            "spring.jpa.open-in-view=false",
            "performance.seat-setup.retry.enabled=false",
            "performance.seat-setup.recovery.enabled=false",
            // 운영 설정(config-repo)은 테스트에서 읽지 않는다. Redis 정지 시험이 Lettuce 기본 60초를 기다리지 않게 운영 값을 넣고,
            // 건너뛰는 시간은 짧게 둬 정지 시험 뒤 다른 테스트가 캐시를 바로 다시 쓰게 한다
            "spring.data.redis.timeout=100ms",
            "performance.detail-cache.open-duration=200ms"
        })
public abstract class PerformanceIntegrationTest extends PostgresIntegrationTest {

    /** 테스트 데이터(sql/public-performance-fixture.sql)의 기준 시각. 매 테스트 전에 이 시각으로 되돌린다. */
    protected static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    /** {@code @ServiceConnection}이 컨테이너 주소를 spring.data.redis.* 대신 넣어 준다. 운영 Compose와 같은 버전이다. */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected FakeSeatSetupClient seatSetupClient;

    @Autowired
    protected StringRedisTemplate redis;

    /**
     * 공개 목록이 한 스냅숏을 읽는지 보는 시험이 조회 도중에 다른 연결의 커밋을 끼워 넣을 때 쓴다.
     * 스텁하지 않으면 실제 구현을 그대로 부르고, 스텁은 테스트마다 초기화된다. 컨텍스트를 하나로 두려고 여기에 선언한다.
     */
    @MockitoSpyBean
    protected PublicPerformanceReader publicPerformanceReader;

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
        seatSetupClient.reset();
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        FakeSeatSetupClient fakeSeatSetupClient(DataSource dataSource) throws SQLException {
            return new FakeSeatSetupClient(dataSource);
        }
    }
}
