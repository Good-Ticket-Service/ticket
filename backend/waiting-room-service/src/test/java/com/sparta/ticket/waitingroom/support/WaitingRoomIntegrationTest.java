package com.sparta.ticket.waitingroom.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * waiting-room-service 통합 테스트의 공통 부모. 실제 Redis 7.4 컨테이너(운영 Compose와 같은 버전)로 서비스를 띄운다. Docker가 실행 중이어야 한다.
 * config-server와 Eureka는 끄고 설정 값은 아래 properties로 준다. DB는 쓰지 않는다.
 * 컨테이너를 static 필드로 한 번만 띄워 모든 테스트 클래스가 공유한다. 테스트끼리 키가 겹치지 않게 테스트마다 다른 room ID를 쓴다.
 * 테스트 클래스가 따로 @Import나 properties를 붙이면 컨텍스트가 따로 뜨므로 설정은 여기에 모은다.
 */
@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "waiting-room.admit-per-second=10",
            "waiting-room.admission.max-active=1000",
            "waiting-room.admission.burst=2s",
            "waiting-room.admission.leave-after=2m",
            "waiting-room.admission.entry-ttl=10m",
            "waiting-room.admission.leave-batch-size=1000",
            "waiting-room.token.secret=test-only-queue-token-secret-0123456789abcdef"
        })
@AutoConfigureMockMvc
@Import(WaitingRoomTestConfig.class)
public abstract class WaitingRoomIntegrationTest {

    /** {@code @ServiceConnection}이 컨테이너 주소를 spring.data.redis.* 대신 넣어 준다. */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    static {
        REDIS.start();
    }
}
