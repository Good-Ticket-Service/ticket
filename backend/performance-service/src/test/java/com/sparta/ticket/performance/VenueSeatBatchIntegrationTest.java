package com.sparta.ticket.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.UserHeaders;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

/** SQL DDL과 전체 시드 뒤 실제 API 저장을 검사한다. 성능 측정과 기존 DB 볼륨은 사용하지 않는다. */
@SpringBootTest(
        properties = {
            "spring.cloud.config.enabled=false",
            "eureka.client.enabled=false",
            "spring.sql.init.mode=always",
            "spring.sql.init.encoding=UTF-8",
            "spring.sql.init.schema-locations=file:../sql/performance-service/schema.sql",
            "spring.sql.init.data-locations=classpath:db/seed-local.sql,classpath:db/seed-load.sql,classpath:db/seed-kopis.sql",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.open-in-view=false",
            "performance.seat-setup.retry.enabled=false",
            "performance.seat-setup.recovery.enabled=false"
        })
@AutoConfigureMockMvc
class VenueSeatBatchIntegrationTest {
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void rewritesBatchesAndPreservesOrderedIdsAndAuditingAfterAllSeeds() throws Exception {
        long venue = 200000001;
        createVenue(venue);
        long max = jdbc.queryForObject("SELECT max(id) FROM p_venue_seats", Long.class);
        jdbc.execute("CREATE TABLE batch_statement_rows (rows_inserted bigint)");
        jdbc.execute("""
                CREATE FUNCTION record_batch_rows() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN INSERT INTO batch_statement_rows SELECT count(*) FROM inserted; RETURN NULL; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER record_batch_rows AFTER INSERT ON p_venue_seats
                REFERENCING NEW TABLE AS inserted FOR EACH STATEMENT EXECUTE FUNCTION record_batch_rows()
                """);
        try {
            JsonNode ids = mapper.readTree(register(venue, "B", 123)
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.data.createdCount").value(123))
                            .andExpect(jsonPath("$.data.venueVersion").value(1))
                            .andReturn()
                            .getResponse()
                            .getContentAsString())
                    .at("/data/createdSeatIds");
            assertThat(ids.size()).isEqualTo(123);
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i).asLong();
                assertThat(id).isGreaterThan(max);
                assertThat(jdbc.queryForObject("SELECT seat_number FROM p_venue_seats WHERE id=?", Integer.class, id))
                        .isEqualTo(i + 1);
            }
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_venue_seats WHERE venue_id=?"
                                    + " AND created_by='batch-admin' AND updated_by='batch-admin'"
                                    + " AND created_at IS NOT NULL AND updated_at IS NOT NULL",
                            Integer.class,
                            venue))
                    .isEqualTo(123);
            // Note: 서버 statement 전이 테이블의 행 수로 드라이버의 다중 행 재작성을 확인한다.
            assertThat(jdbc.queryForObject("SELECT sum(rows_inserted) FROM batch_statement_rows", Long.class))
                    .isEqualTo(123);
            assertThat(jdbc.queryForObject("SELECT max(rows_inserted) FROM batch_statement_rows", Long.class))
                    .isGreaterThan(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM batch_statement_rows", Long.class))
                    .isLessThan(123);
        } finally {
            jdbc.execute("DROP TRIGGER record_batch_rows ON p_venue_seats");
            jdbc.execute("DROP FUNCTION record_batch_rows()");
            jdbc.execute("DROP TABLE batch_statement_rows");
        }
    }

    @Test
    void laterBatchUniqueViolationReturns409AndRollsBackEarlierStatements() throws Exception {
        long venue = 200000002;
        createVenue(venue);
        jdbc.update(
                "INSERT INTO p_venue_seats (venue_id,venue_seat_grade_id,section,row_label,seat_number,"
                        + "created_at,created_by,updated_at,updated_by) VALUES (?,?,'A','1',1,now(),'SYSTEM',now(),'SYSTEM')",
                venue,
                venue);
        jdbc.execute("CREATE SEQUENCE completed_batch_statements");
        jdbc.execute("""
                CREATE FUNCTION observe_completed_batch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM nextval('completed_batch_statements'); RETURN NULL; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER observe_completed_batch AFTER INSERT ON p_venue_seats
                FOR EACH STATEMENT EXECUTE FUNCTION observe_completed_batch()
                """);
        jdbc.execute("""
                CREATE FUNCTION fail_later_batch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.section='FAIL' AND NEW.seat_number=121 THEN
                        NEW.section := 'A'; NEW.seat_number := 1;
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER fail_later_batch BEFORE INSERT ON p_venue_seats"
                + " FOR EACH ROW EXECUTE FUNCTION fail_later_batch()");
        try {
            register(venue, "FAIL", 123)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_VENUE_SEAT"));
            // 시퀀스는 롤백되지 않는다. 실패 전에 실제 성공한 INSERT statement가 있었다는 증거다.
            assertThat(jdbc.queryForObject("SELECT is_called FROM completed_batch_statements", Boolean.class))
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT last_value FROM completed_batch_statements", Long.class))
                    .isGreaterThan(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_venue_seats WHERE venue_id=?", Integer.class, venue))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT version FROM p_venues WHERE id=?", Long.class, venue))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_later_batch ON p_venue_seats");
            jdbc.execute("DROP TRIGGER observe_completed_batch ON p_venue_seats");
            jdbc.execute("DROP FUNCTION fail_later_batch()");
            jdbc.execute("DROP FUNCTION observe_completed_batch()");
            jdbc.execute("DROP SEQUENCE completed_batch_statements");
        }
    }

    private void createVenue(long id) {
        jdbc.update(
                "INSERT INTO p_venues (id,name,address,region,created_at,created_by,updated_at,updated_by)"
                        + " VALUES (?,'배치 시험','주소','서울',now(),'SYSTEM',now(),'SYSTEM')",
                id);
        jdbc.update(
                "INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by)"
                        + " VALUES (?,?,'VIP',now(),'SYSTEM',now(),'SYSTEM')",
                id,
                id);
    }

    private ResultActions register(long venue, String section, int count) throws Exception {
        String seats = IntStream.rangeClosed(1, count)
                .mapToObj(i -> "{\"section\":\"" + section + "\",\"rowLabel\":\"1\",\"seatNumber\":" + i
                        + ",\"venueSeatGradeId\":" + venue + "}")
                .collect(Collectors.joining(","));
        return mvc.perform(post("/api/admin/venues/" + venue + "/seats")
                .header(UserHeaders.USER_ID, "1")
                .header(UserHeaders.USERNAME, "batch-admin")
                .header(UserHeaders.ROLE, "MASTER")
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[" + seats + "]}"));
    }
}
