package com.sparta.ticket.performance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** local·CI처럼 Hibernate가 테이블을 만든 뒤 실제 시드 두 개를 넣어 기동한다. */
@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create",
            "spring.jpa.defer-datasource-initialization=true",
            "spring.sql.init.mode=always",
            "spring.sql.init.data-locations=classpath:db/seed-local.sql,classpath:db/seed-load.sql",
            "spring.sql.init.encoding=UTF-8",
            "performance.seat-setup.retry.enabled=false",
            "performance.seat-setup.recovery.enabled=false"
        })
class HibernateSeedStartupIntegrationTest {
    // Note: schema.sql 검증용 공통 DB와 분리해 다른 테스트의 테이블을 다시 만들지 않는다.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void startsWithBothSeedsAndInitializesRecoveryCounters() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_schedules", Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_schedules WHERE seat_setup_recovery_attempts=0"
                                + " AND seat_setup_recovery_limit=0 AND seat_setup_failure_kind IS NULL"
                                + " AND seat_setup_recovery_next_at IS NULL",
                        Integer.class))
                .isEqualTo(13);
    }
}
