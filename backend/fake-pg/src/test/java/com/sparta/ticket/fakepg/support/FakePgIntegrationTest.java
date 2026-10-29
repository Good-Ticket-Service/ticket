package com.sparta.ticket.fakepg.support;

import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.domain.repository.IdempotencyRecordRepository;
import com.sparta.ticket.fakepg.domain.repository.PgTransactionRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실제 PostgreSQL 16 컨테이너로 fake PG를 띄우는 통합 테스트의 부모. Docker가 실행 중이어야 한다.
 * 팀 공통 테스트 기반(PostgresIntegrationTest)을 쓰지 않는 이유: 그 모듈을 넣으면 팀 공통 예외 처리가 함께 올라와
 * PG 형식의 오류 응답이 바뀐다. 운영과 같은 schema.sql(jar 안의 db/schema.sql)로 테이블을 만들고 Entity와 대조한다.
 */
@SpringBootTest(
        properties = {
            "fake-pg.toss.secret-key=" + FakePgIntegrationTest.SECRET_KEY,
            "fake-pg.kakao.secret-key=" + FakePgIntegrationTest.KAKAO_SECRET_KEY
        })
@AutoConfigureMockMvc
public abstract class FakePgIntegrationTest {

    public static final String SECRET_KEY = "test_sk_integration";
    public static final String AUTHORIZATION =
            "Basic " + Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));

    public static final String KAKAO_SECRET_KEY = "DEV_integration";
    public static final String KAKAO_AUTHORIZATION = "SECRET_KEY " + KAKAO_SECRET_KEY;
    public static final String KAKAO_CID = "TC0ONETIME";

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected FaultInjector faults;

    @Autowired
    private PgTransactionRepository transactions;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecords;

    @BeforeEach
    void resetState() {
        faults.reset();
        faults.resetStats();
        faults.reseed(null);
        idempotencyRecords.deleteAllRecords();
        transactions.deleteAllTransactions();
    }
}
