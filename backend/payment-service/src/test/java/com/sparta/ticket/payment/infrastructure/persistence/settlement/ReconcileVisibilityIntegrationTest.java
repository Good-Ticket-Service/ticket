package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** 업무 ID·시각 대신 저장된 스냅샷으로 늦은 커밋을 빠짐없이 선택하는 PostgreSQL 16 선행 시험. */
class ReconcileVisibilityIntegrationTest {
    @Test
    void snapshotDifferenceIncludesLateSmallIdAndExcludesRollbackAndPreviouslyVisible() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var control = connect(postgres);
                    var late = connect(postgres);
                    var fast = connect(postgres);
                    var observer = connect(postgres)) {
                execute(
                        control,
                        "CREATE TABLE visibility_probe(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,writer_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),amount bigint NOT NULL)");
                execute(control, "CREATE INDEX visibility_probe_xid ON visibility_probe(writer_xid)");
                late.setAutoCommit(false);
                fast.setAutoCommit(false);
                assertThat(value(late, "INSERT INTO visibility_probe(amount) VALUES(100) RETURNING id"))
                        .isEqualTo("1");
                assertThat(value(fast, "INSERT INTO visibility_probe(amount) VALUES(200) RETURNING id"))
                        .isEqualTo("2");
                fast.commit();
                observer.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                observer.setAutoCommit(false);
                String previous = value(observer, "SELECT pg_current_snapshot()::text");
                assertThat(value(observer, "SELECT sum(amount) FROM visibility_probe"))
                        .isEqualTo("200");
                // Note: 검사 스냅샷 뒤 작은 ID가 커밋돼도 같은 RR에서는 아직 보이지 않는다.
                late.commit();
                assertThat(value(observer, "SELECT sum(amount) FROM visibility_probe"))
                        .isEqualTo("200");
                observer.commit();
                try (var rolledBack = connect(postgres)) {
                    rolledBack.setAutoCommit(false);
                    execute(rolledBack, "INSERT INTO visibility_probe(amount) VALUES(400)");
                    rolledBack.rollback();
                }
                execute(fast, "INSERT INTO visibility_probe(amount) VALUES(300)");
                fast.commit();
                String predicate = "writer_xid>=pg_snapshot_xmin('" + previous
                        + "'::pg_snapshot) AND NOT pg_visible_in_snapshot(writer_xid,'" + previous + "'::pg_snapshot)";
                assertThat(ids(observer, "SELECT id FROM visibility_probe WHERE " + predicate + " ORDER BY id"))
                        .containsExactly(1L, 4L);
                assertThat(value(observer, "SELECT 200+sum(amount) FROM visibility_probe WHERE " + predicate))
                        .isEqualTo(value(observer, "SELECT sum(amount) FROM visibility_probe"));
                observer.commit();
                execute(control, "SET enable_seqscan=off");
                try (var statement = control.createStatement();
                        var plan = statement.executeQuery(
                                "EXPLAIN (FORMAT JSON) SELECT id FROM visibility_probe WHERE " + predicate)) {
                    plan.next();
                    assertThat(plan.getString(1)).contains("visibility_probe_xid");
                }
                // Note: 저장한 snapshot 텍스트는 원래 관측 트랜잭션 종료 뒤에도 다른 연결에서 사용할 수 있다.
                assertThat(value(control, "SELECT sum(amount) FROM visibility_probe WHERE " + predicate))
                        .isEqualTo("400");
            }
        }
    }

    @Test
    void topLevelWriterXidSurvivesSavepointAndPreventsLateEntryAppend() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (var connection = connect(postgres)) {
                execute(connection, "CREATE TABLE header_probe(id bigint PRIMARY KEY,writer_xid xid8)");
                execute(connection, "CREATE TABLE entry_probe(header_id bigint REFERENCES header_probe,amount bigint)");
                execute(connection, """
                        CREATE FUNCTION stamp_probe() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                        NEW.writer_xid:=pg_current_xact_id(); RETURN NEW; END $$;
                        CREATE TRIGGER stamp BEFORE INSERT ON header_probe FOR EACH ROW EXECUTE FUNCTION stamp_probe();
                        CREATE FUNCTION entry_probe_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                        IF NOT EXISTS(SELECT 1 FROM header_probe WHERE id=NEW.header_id
                            AND writer_xid=pg_current_xact_id()) THEN
                          RAISE EXCEPTION 'late entry append' USING ERRCODE='23514'; END IF;
                        RETURN NEW; END $$;
                        CREATE TRIGGER entry_guard BEFORE INSERT ON entry_probe FOR EACH ROW EXECUTE FUNCTION entry_probe_guard();
                        """);
                connection.setAutoCommit(false);
                execute(connection, "INSERT INTO header_probe VALUES(1,NULL)");
                String xid = value(connection, "SELECT writer_xid::text FROM header_probe WHERE id=1");
                execute(connection, "SAVEPOINT ledger_lines");
                assertThat(value(connection, "SELECT pg_current_xact_id()::text"))
                        .isEqualTo(xid);
                execute(connection, "INSERT INTO entry_probe VALUES(1,10),(1,-10)");
                execute(connection, "RELEASE SAVEPOINT ledger_lines");
                connection.commit();
                connection.setAutoCommit(true);
                assertThatThrownBy(() -> execute(connection, "INSERT INTO entry_probe VALUES(1,1)"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("late entry append");
                assertThat(value(connection, "SELECT count(*) FROM entry_probe"))
                        .isEqualTo("2");
            }
        }
    }

    private static Connection connect(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String value(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static List<Long> ids(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            List<Long> ids = new ArrayList<>();
            while (result.next()) ids.add(result.getLong(1));
            return ids;
        }
    }
}
