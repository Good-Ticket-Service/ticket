package com.sparta.ticket.reservation.support;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.reservation.booking.support.BookingTestConfig;
import com.sparta.ticket.reservation.seat.support.SeatTestConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * reservation-service 통합 테스트의 공통 부모. booking·seat·coupon 등 모든 모듈의 통합 테스트가 이 클래스를 상속해 같은 설정을 쓴다.
 * 모듈이 DDL 파일을 더하면 아래 schema-locations에 한 줄 추가한다. 모듈 테스트가 따로 @Import나 @TestPropertySource를 붙이지 않는다.
 *
 * 스키마는 모듈이 함께 쓰는 schema.sql 다음에 booking이 소유한 booking.sql, seat가 소유한 seat.sql, coupon이 소유한 coupon.sql을
 * 실행해 만든다(docs/architecture.md 13절의 #57 합의, docs/tables.md). PostgresIntegrationTest는 schema.sql 하나만 실행하므로 여기서 바꾼다. @TestPropertySource 값이 @SpringBootTest의 properties보다 우선한다.
 *
 * Note: PostgresIntegrationTest는 컨테이너를 하나만 띄워 공유하지만, 스키마 스크립트는 Spring 컨텍스트가 뜰 때마다 실행된다.
 * 테스트 클래스마다 @Import나 @TestPropertySource가 다르면 컨텍스트가 따로 떠서 두 번째 CREATE TABLE이 "이미 있음"으로 실패한다.
 * 설정을 한곳에 모아 컨텍스트를 하나로 유지한다.
 * open-in-view는 운영(config-repo/application.yml)과 같이 끈다. 켜 두면 요청 내내 DB 연결을 잡아
 * "원격 호출은 트랜잭션 밖" 검사가 운영과 다른 조건에서 통과한다.
 *
 * 대기열(queue)은 Redis를 쓰므로 Redis 7.4 컨테이너도 여기서 한 번만 띄운다. 운영 Compose와 같은 버전이다.
 * 대기열 테스트용 부모를 따로 두면 컨텍스트가 둘로 갈려 위의 스키마 문제가 생기므로, 같은 부모에 둔다.
 */
@AutoConfigureMockMvc
@Import({BookingTestConfig.class, SeatTestConfig.class})
@TestPropertySource(
        properties = {
            "spring.sql.init.schema-locations="
                    + "file:../sql/reservation-service/schema.sql,file:../sql/reservation-service/booking.sql,"
                    + "file:../sql/reservation-service/seat.sql,file:../sql/reservation-service/coupon.sql",
            "reservation.booking.scheduler.enabled=false",
            "reservation.queue.enabled=true",
            "reservation.queue.admit-per-second=10",
            "spring.jpa.open-in-view=false"
        })
public abstract class ReservationIntegrationTest extends PostgresIntegrationTest {

    /** {@code @ServiceConnection}이 컨테이너 주소를 spring.data.redis.* 대신 넣어 준다. */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    static {
        REDIS.start();
    }
}
