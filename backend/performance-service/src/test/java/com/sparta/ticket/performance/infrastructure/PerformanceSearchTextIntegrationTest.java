package com.sparta.ticket.performance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.application.port.PerformanceStore;
import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이슈 #441 검색용 텍스트(search_text). schema.sql의 트리거가 모든 쓰기 경로에서 값을 채우고,
 * 키워드 조건이 이슈 #442의 필드별 공백 제거 비교와 같은 공연을 고르며 필드 경계를 넘어 맞지 않는지 확인한다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PerformanceSearchTextIntegrationTest extends PerformanceIntegrationTest {

    private static final String SEP = "\u001F";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PerformanceStore performanceStore;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("SQL로 넣은 공연도 제목·출연진·공연장명을 공백 제거·소문자로 바꿔 구분 문자로 잇는다")
    void fillsSearchTextOnSqlInsert() {
        assertThat(searchText(10)).isEqualTo("뮤지컬alpha" + SEP + "배우a,배우b" + SEP + "서울공연장");
        // 출연진이 없으면 빈 칸으로 자리만 둔다
        assertThat(searchText(11)).isEqualTo("콘서트beta" + SEP + SEP + "부산공연장");
        // 삭제된 공연, 삭제된 공연장의 공연도 채운다
        assertThat(searchText(21)).isEqualTo("뮤지컬deleted" + SEP + SEP + "서울공연장");
        assertThat(searchText(23)).isEqualTo("뮤지컬lostvenue" + SEP + SEP + "삭제된공연장");
    }

    @Test
    @DisplayName("Entity로 등록한 공연도 트리거가 채운다")
    void fillsSearchTextOnJpaInsert() {
        Long id = transactions.execute(status -> performanceStore
                .save(Performance.createDraft(
                        900L, 2L, "피아노 독주회", Genre.CLASSICAL, AgeRating.ALL, null, 90, "Kim Pianist", "소개", null))
                .getId());

        assertThat(searchText(id)).isEqualTo("피아노독주회" + SEP + "kimpianist" + SEP + "부산공연장");
    }

    @Test
    @DisplayName("제목·출연진·공연장을 바꾸면 다시 계산하고, 공연장명이 바뀌면 그 공연장의 공연을 모두 다시 계산한다")
    void refreshesSearchTextOnWrites() {
        jdbc.update("UPDATE p_performances SET title = 'New Title', cast_info = '새 배우' WHERE id = 10");
        assertThat(searchText(10)).isEqualTo("newtitle" + SEP + "새배우" + SEP + "서울공연장");

        jdbc.update("UPDATE p_performances SET venue_id = 2 WHERE id = 10");
        assertThat(searchText(10)).isEqualTo("newtitle" + SEP + "새배우" + SEP + "부산공연장");

        jdbc.update("UPDATE p_venues SET name = '새 공연장 이름' WHERE id = 1");
        assertThat(searchText(12)).isEqualTo("연극gamma" + SEP + SEP + "새공연장이름");
        assertThat(searchText(21)).isEqualTo("뮤지컬deleted" + SEP + SEP + "새공연장이름");
        // 다른 공연장으로 옮긴 공연은 바뀌지 않는다
        assertThat(searchText(10)).isEqualTo("newtitle" + SEP + "새배우" + SEP + "부산공연장");

        // 검색에 쓰지 않는 컬럼만 바꾸면 값이 그대로다
        jdbc.update("UPDATE p_venues SET address = '다른 주소' WHERE id = 2");
        jdbc.update("UPDATE p_performances SET description = '다른 안내' WHERE id = 11");
        assertThat(searchText(11)).isEqualTo("콘서트beta" + SEP + SEP + "부산공연장");
    }

    /**
     * 공연장명 변경이 커밋되기 전에 다른 연결이 그 공연장으로 공연을 넣거나 옮기면, 공연장 트리거의 일괄 갱신에는 그 행이 보이지 않는다.
     * 공연 트리거가 공연장 행을 공유 잠금으로 읽어 변경 커밋을 기다리고 바뀐 이름으로 채우는지 본다(이슈 #441 리뷰 R1-1).
     * Note: 시간만 기다리면 작업이 늦게 시작해 커밋 뒤에 SQL을 보내도 통과한다. 두 작업의 서버 세션이 이름 변경 세션에 막혀 있는지
     * pg_blocking_pids로 확인한 뒤 커밋한다. 공유 잠금이 없으면 막히지 않아 시험이 실패한다(리뷰 R2-1).
     */
    @Test
    @DisplayName("커밋 전인 공연장명 변경과 겹친 SQL 등록·공연장 이동도 바뀐 이름으로 채운다")
    void waitsForUncommittedVenueRename() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        // pids와 writes는 같은 순서다. 정리할 때 끝나지 않은 작업의 서버 질의를 PID로 취소한다
        List<CompletableFuture<Integer>> pids = new ArrayList<>();
        List<CompletableFuture<Integer>> writes = new ArrayList<>();
        Connection renamer = dataSource.getConnection();
        boolean committed = false;
        try {
            renamer.setAutoCommit(false);
            int renamerPid = backendPid(renamer);
            try (Statement statement = renamer.createStatement()) {
                statement.executeUpdate("UPDATE p_venues SET name = '새 이름 홀' WHERE id = 1");
            }
            pids.add(new CompletableFuture<>());
            writes.add(updateOnOwnConnection(
                    workers,
                    pids.get(0),
                    "INSERT INTO p_performances (id, seller_id, venue_id, title, genre, running_time,"
                            + " created_at, created_by, updated_at, updated_by)"
                            + " VALUES (30, 501, 1, '새 공연', 'PLAY', 90, now(), 'SYSTEM', now(), 'SYSTEM')"));
            pids.add(new CompletableFuture<>());
            writes.add(updateOnOwnConnection(
                    workers, pids.get(1), "UPDATE p_performances SET venue_id = 1 WHERE id = 11"));

            for (CompletableFuture<Integer> pid : pids) {
                awaitBlockedBy(pid.get(10, TimeUnit.SECONDS), renamerPid);
            }

            renamer.commit();
            committed = true;
            for (CompletableFuture<Integer> write : writes) {
                assertThat(write.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            }
        } catch (Throwable failure) {
            // 정리 실패가 원래 실패를 가리지 않게, 정리 중 오류는 원래 실패에 덧붙인다
            Exception cleanup = releaseAndDrain(renamer, committed, pids, writes, workers);
            if (cleanup != null) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        Exception cleanup = releaseAndDrain(renamer, committed, pids, writes, workers);
        if (cleanup != null) {
            throw cleanup;
        }

        assertThat(searchText(30)).isEqualTo("새공연" + SEP + SEP + "새이름홀");
        assertThat(searchText(11)).isEqualTo("콘서트beta" + SEP + SEP + "새이름홀");
        assertThat(searchText(10)).isEqualTo("뮤지컬alpha" + SEP + "배우a,배우b" + SEP + "새이름홀");
    }

    @Test
    @DisplayName("필드 경계를 넘는 문자열은 일치로 보지 않는다")
    void doesNotMatchAcrossFields() throws Exception {
        jdbc.update("UPDATE p_performances SET title = '가나 다', cast_info = '라마 바' WHERE id = 10");
        jdbc.update("UPDATE p_venues SET name = '사아 자' WHERE id = 1");

        // 제목 끝 + 출연진 앞, 출연진 끝 + 공연장명 앞, 세 필드 전체
        for (String keyword : List.of("다라", "바사", "가나다라마바사아자")) {
            search(keyword)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
        // 한 필드 안에서는 띄어쓰기를 넘어 맞는다(이슈 #442)
        search("나다").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        search("마바").andExpect(jsonPath("$.data.content[*].id").value(contains(10)));
        search("아자").andExpect(jsonPath("$.data.content[*].id").value(hasItem(10)));
    }

    @Test
    @DisplayName("검색용 텍스트 비교는 필드별 공백 제거 비교와 같은 공연을 고른다")
    void matchesFieldwiseComparison() {
        jdbc.update("UPDATE p_performances SET cast_info = 'Alpha 합창단, 배우_C 100%!' WHERE id = 12");
        jdbc.update("UPDATE p_venues SET name = 'Gamma Hall' WHERE id = 2");
        List<String> keywords = List.of(
                "alpha", "ALPHA", "뮤지컬", "공연장", "서울 공연장", "배우A", "배우_c", "_", "%", "!", "100%!", "a,배",
                "gamma", "hall", "부산", "delta", "없는말", "lostvenue");

        int matched = 0;
        for (String keyword : keywords) {
            String pattern = containsPattern(keyword);
            List<Long> fieldwise = jdbc.queryForList(
                    "SELECT p.id FROM p_performances p JOIN p_venues v ON v.id = p.venue_id"
                            + " WHERE lower(replace(p.title, ' ', '')) LIKE ? ESCAPE '!'"
                            + " OR lower(replace(v.name, ' ', '')) LIKE ? ESCAPE '!'"
                            + " OR lower(replace(p.cast_info, ' ', '')) LIKE ? ESCAPE '!' ORDER BY p.id",
                    Long.class,
                    pattern,
                    pattern,
                    pattern);
            List<Long> searchText = jdbc.queryForList(
                    "SELECT p.id FROM p_performances p WHERE p.search_text LIKE ? ESCAPE '!' ORDER BY p.id",
                    Long.class,
                    pattern);
            assertThat(searchText).as(keyword).isEqualTo(fieldwise);
            matched += fieldwise.size();
        }
        assertThat(matched).isPositive();
    }

    /**
     * 대기 시험의 뒷정리. 다음 시험의 fixture 초기화(TRUNCATE)가 남은 잠금·질의에 막히지 않게 한다(리뷰 R3-1).
     * 단계마다 오류를 모으고 다음 단계로 넘어간다. 한 단계가 실패해도 뒤 단계(작업 회수, 실행기 종료)를 건너뛰지 않는다.
     * 1. 커밋 전이면 롤백하고 이름 변경 연결을 닫아 공연장 잠금을 푼다.
     * 2. 작업을 10초씩 기다린다. 끝나지 않으면 pg_cancel_backend로 서버 질의를 취소하고 다시 기다린다.
     *    스레드 중단이나 future 취소만으로는 서버의 SQL이 끝나지 않기 때문이다.
     * 3. 실행기를 종료하고 스레드가 끝났는지 확인한다.
     * Note: PID를 받기 전(연결을 얻는 중)에 멈춘 작업은 취소할 질의가 없어 실행기 종료 확인에 맡긴다.
     */
    private Exception releaseAndDrain(
            Connection renamer,
            boolean committed,
            List<CompletableFuture<Integer>> pids,
            List<CompletableFuture<Integer>> writes,
            ExecutorService workers) {
        List<Exception> errors = new ArrayList<>();
        try {
            if (!committed) {
                renamer.rollback();
            }
        } catch (SQLException exception) {
            errors.add(exception);
        }
        try {
            renamer.close();
        } catch (SQLException exception) {
            errors.add(exception);
        }
        boolean interrupted = Thread.interrupted();
        for (int i = 0; i < writes.size(); i++) {
            if (!awaitQuietly(writes.get(i), errors)) {
                Integer pid = pids.get(i).getNow(null);
                if (pid != null) {
                    try {
                        jdbc.queryForObject("SELECT pg_cancel_backend(?)", Boolean.class, pid);
                    } catch (RuntimeException exception) {
                        errors.add(exception);
                    }
                }
                if (!awaitQuietly(writes.get(i), errors)) {
                    errors.add(new IllegalStateException("작업 " + i + "(PID " + pid + ")이 취소 뒤에도 끝나지 않았습니다"));
                }
            }
        }
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                errors.add(new IllegalStateException("작업 스레드가 끝나지 않았습니다"));
            }
        } catch (InterruptedException exception) {
            errors.add(exception);
        }
        if (interrupted || errors.stream().anyMatch(InterruptedException.class::isInstance)) {
            Thread.currentThread().interrupt();
        }
        if (errors.isEmpty()) {
            return null;
        }
        IllegalStateException failure = new IllegalStateException("대기 시험 정리 실패");
        errors.forEach(failure::addSuppressed);
        return failure;
    }

    /** 작업이 10초 안에 끝나면 true. 작업 자체의 실패는 본문에서 확인하므로 여기서는 끝났는지만 본다. */
    private static boolean awaitQuietly(CompletableFuture<Integer> write, List<Exception> errors) {
        try {
            write.get(10, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException exception) {
            return true;
        } catch (TimeoutException exception) {
            return false;
        } catch (InterruptedException exception) {
            // 인터럽트 상태에서도 정리를 이어 가도록 표시만 지우고 기록한다. 호출한 쪽이 끝에 되살린다
            errors.add(exception);
            return write.isDone();
        }
    }

    /** 전용 연결에서 서버 세션 PID를 알린 뒤 SQL을 실행한다. 자동 커밋이다. */
    private CompletableFuture<Integer> updateOnOwnConnection(
            ExecutorService workers, CompletableFuture<Integer> pid, String sql) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (Connection connection = dataSource.getConnection();
                            Statement statement = connection.createStatement()) {
                        pid.complete(backendPid(connection));
                        return statement.executeUpdate(sql);
                    } catch (SQLException exception) {
                        pid.completeExceptionally(exception);
                        throw new IllegalStateException(exception);
                    }
                },
                workers);
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    /** waiter 세션이 blocker 세션의 잠금을 기다리는 상태가 될 때까지 최대 10초 확인한다. */
    private void awaitBlockedBy(int waiterPid, int blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject(
                    "SELECT ?::int = ANY(pg_blocking_pids(?::int))", Boolean.class, blockerPid, waiterPid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("세션 " + waiterPid + "이 공연장명 변경 세션 " + blockerPid + "에 막히지 않았습니다");
    }

    private String searchText(long performanceId) {
        return jdbc.queryForObject(
                "SELECT search_text FROM p_performances WHERE id = ?", String.class, performanceId);
    }

    private ResultActions search(String keyword) throws Exception {
        return mockMvc.perform(get("/api/performances")
                .queryParam("keyword", keyword)
                .queryParam("status", "ON_SALE,UPCOMING,ENDED"));
    }

    /** 저장소와 같은 패턴: 스페이스를 지우고(KeywordParameter) 소문자로 바꾼 뒤 %·_·!를 이스케이프한다. */
    private static String containsPattern(String keyword) {
        String normalized = keyword.replace(" ", "").toLowerCase(Locale.ROOT);
        return "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
