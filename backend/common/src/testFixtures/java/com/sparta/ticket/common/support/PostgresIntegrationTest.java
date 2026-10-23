package com.sparta.ticket.common.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실제 PostgreSQL 16 컨테이너로 서비스를 띄우는 통합 테스트의 공통 부모. Docker가 실행 중이어야 한다.
 * config-server와 Eureka는 끄고, 스키마는 backend/sql/{서비스}/schema.sql로 만든 뒤 Entity와 맞는지 검사한다(ddl-auto=validate).
 *
 * Note: local 프로필은 Hibernate가 Entity로 테이블을 만들기(create) 때문에 손으로 쓴 schema.sql이 Entity와 어긋나도 모른다.
 * 테스트에서는 schema.sql을 먼저 실행하고(spring.sql.init) Hibernate는 검증만 시켜, 어긋나면 컨텍스트가 뜨지 않게 한다.
 * SQL 초기화는 기본으로 Hibernate보다 먼저 실행된다. 경로의 ..은 Gradle 테스트 작업 폴더(모듈 폴더) 기준이다.
 *
 * 컨테이너를 static 필드로 한 번만 띄워 모든 테스트 클래스가 공유한다(싱글턴 컨테이너 패턴).
 * {@code @ServiceConnection}이 컨테이너의 접속 정보(url, username, password)를 spring.datasource.* 대신 넣어 준다.
 * H2 같은 대체 DB를 쓰지 않는 이유: 부분 인덱스, 네이티브 쿼리, 유니크 제약 동작이 운영 DB와 같아야 검증 의미가 있다.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:../sql/${spring.application.name}/schema.sql",
        "spring.jpa.hibernate.ddl-auto=validate"
})
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }
}
