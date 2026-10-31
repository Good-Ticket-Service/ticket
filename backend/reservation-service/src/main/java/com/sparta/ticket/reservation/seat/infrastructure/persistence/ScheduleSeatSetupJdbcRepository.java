package com.sparta.ticket.reservation.seat.infrastructure.persistence;

import com.sparta.ticket.reservation.seat.domain.ScheduleSeatCounts;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSetupRepository;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSnapshot;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatSpec;
import com.sparta.ticket.reservation.seat.domain.SeatStatus;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 좌석 준비용 JDBC 저장소. 좌석 PK가 IDENTITY라 JPA는 INSERT마다 생성된 키를 받아야 해서 묶어 보내지 못한다.
 * 그래서 좌석 준비처럼 한 번에 많은 행을 쓰는 경로는 JdbcTemplate.batchUpdate로 쓴다.
 *
 * Note: JpaTransactionManager가 트랜잭션의 JDBC 연결을 JdbcTemplate에도 넘겨주므로 서비스의 @Transactional 안에서 같은 연결·같은
 * 트랜잭션으로 실행된다. 영속성 컨텍스트를 거치지 않으므로, 같은 트랜잭션에서 ScheduleSeat Entity를 함께 읽어 판단하지 않는다.
 * Note: 감사 컬럼(created_at 등)은 JPA 감사를 거치지 않으므로 넘겨받은 값으로 직접 채운다(조건부 UPDATE와 같은 방식).
 */
@Repository
@RequiredArgsConstructor
public class ScheduleSeatSetupJdbcRepository implements ScheduleSeatSetupRepository {

    /** advisory 잠금 키 접두어. 잠금 키는 DB 전체(스키마와 무관)에서 공유되므로 다른 서비스의 키와 겹치지 않게 이름을 붙인다. */
    private static final String LOCK_KEY_PREFIX = "seat-setup:";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 잠금 대기 상한. 공연 서비스의 Feign read 타임아웃(5초, #175)보다 짧게 둔다. 길게 기다리는 동안 공연 쪽이 타임아웃 뒤 같은 요청을
     * 다시 보내면 요청마다 DB 연결을 하나씩 쥔 채 기다리게 되고, 이 연결 풀은 선점과 같이 쓴다. 상한을 넘기면 오류로 끝내고
     * 공연 쪽이 같은 requestId로 다시 보내게 한다. 같은 버전은 멱등이라 늦은 재시도도 같은 결과를 받는다.
     */
    private static final String LOCK_TIMEOUT = "3s";

    /** PostgreSQL lock_not_available. lock_timeout을 넘기면 이 SQLSTATE로 끝난다. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /**
     * 이 트랜잭션의 잠금 대기 상한을 건 뒤, 문자열 키를 hashtextextended로 bigint로 바꿔 트랜잭션 범위 잠금을 잡는다.
     * 커밋·롤백 때 풀린다. lock_timeout은 SET LOCAL이라 이 트랜잭션에만 적용되고, advisory 잠금과 이후 FOR UPDATE 대기 모두에 걸린다.
     */
    @Override
    public void lockSchedule(Long scheduleId) {
        jdbcTemplate.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
        translateLockTimeout(() -> jdbcTemplate.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
                Integer.class,
                LOCK_KEY_PREFIX + scheduleId));
    }

    private static final String SELECT_SNAPSHOTS = """
            SELECT id, venue_seat_id, status, config_version, deleted_at IS NOT NULL AS deleted
              FROM p_schedule_seats
             WHERE schedule_id = ?
             ORDER BY id
            """;

    private static final RowMapper<ScheduleSeatSnapshot> SNAPSHOT = (rs, rowNum) -> new ScheduleSeatSnapshot(
            rs.getLong("id"),
            rs.getLong("venue_seat_id"),
            SeatStatus.valueOf(rs.getString("status")),
            rs.getLong("config_version"),
            rs.getBoolean("deleted"));

    /** 논리 삭제된 행도 다시 쓸 수 있어야 하므로 @SQLRestriction이 붙는 Entity 조회 대신 SQL로 읽는다. */
    @Override
    public List<ScheduleSeatSnapshot> findAllIncludingDeleted(Long scheduleId) {
        return jdbcTemplate.query(SELECT_SNAPSHOTS, SNAPSHOT, scheduleId);
    }

    @Override
    public List<ScheduleSeatSnapshot> findAllForUpdateIncludingDeleted(Long scheduleId) {
        return translateLockTimeout(() -> jdbcTemplate.query(SELECT_SNAPSHOTS + " FOR UPDATE", SNAPSHOT, scheduleId));
    }

    @Override
    public void insertAll(
            Long scheduleId, long configVersion, List<ScheduleSeatSpec> seats, LocalDateTime at, String by) {
        if (seats.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.valueOf(at);
        List<Object[]> rows = new ArrayList<>(seats.size());
        for (ScheduleSeatSpec seat : seats) {
            rows.add(new Object[] {
                scheduleId,
                seat.venueSeatId(),
                seat.venueSeatGradeId(),
                seat.gradeName(),
                seat.basePrice(),
                seat.section(),
                seat.rowLabel(),
                seat.seatNumber(),
                seat.sellable(),
                configVersion,
                now,
                by,
                now,
                by
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,
                                              section, row_label, seat_number, is_sellable, status, reservation_id,
                                              hold_expires_at, config_version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', NULL, NULL, ?, ?, ?, ?, ?)
                """, rows, new int[] {
            Types.BIGINT,
            Types.BIGINT,
            Types.BIGINT,
            Types.VARCHAR,
            Types.INTEGER,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.INTEGER,
            Types.BOOLEAN,
            Types.BIGINT,
            Types.TIMESTAMP,
            Types.VARCHAR,
            Types.TIMESTAMP,
            Types.VARCHAR
        });
    }

    /** 위치·등급·가격·판매 여부와 구성 버전을 새 값으로 바꾸고, 삭제 표시를 지운다. 교체는 모든 좌석이 AVAILABLE일 때만 하므로 점유 값도 비운다. */
    @Override
    public void updateAll(long configVersion, Map<Long, ScheduleSeatSpec> seatsById, LocalDateTime at, String by) {
        if (seatsById.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.valueOf(at);
        List<Object[]> rows = new ArrayList<>(seatsById.size());
        seatsById.forEach((id, seat) -> rows.add(new Object[] {
            seat.venueSeatGradeId(),
            seat.gradeName(),
            seat.basePrice(),
            seat.section(),
            seat.rowLabel(),
            seat.seatNumber(),
            seat.sellable(),
            configVersion,
            now,
            by,
            id
        }));
        jdbcTemplate.batchUpdate("""
                UPDATE p_schedule_seats
                   SET venue_seat_grade_id = ?, grade_name = ?, base_price = ?, section = ?, row_label = ?,
                       seat_number = ?, is_sellable = ?, config_version = ?,
                       status = 'AVAILABLE', reservation_id = NULL, hold_expires_at = NULL,
                       updated_at = ?, updated_by = ?, deleted_at = NULL, deleted_by = NULL
                 WHERE id = ?
                """, rows, new int[] {
            Types.BIGINT,
            Types.VARCHAR,
            Types.INTEGER,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.INTEGER,
            Types.BOOLEAN,
            Types.BIGINT,
            Types.TIMESTAMP,
            Types.VARCHAR,
            Types.BIGINT
        });
    }

    @Override
    public void softDeleteAll(long configVersion, Collection<Long> ids, LocalDateTime at, String by) {
        if (ids.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.valueOf(at);
        List<Object[]> rows = ids.stream()
                .map(id -> new Object[] {configVersion, now, by, now, by, id})
                .toList();
        jdbcTemplate.batchUpdate("""
                UPDATE p_schedule_seats
                   SET config_version = ?, deleted_at = ?, deleted_by = ?, updated_at = ?, updated_by = ?
                 WHERE id = ? AND deleted_at IS NULL
                """, rows);
    }

    @Override
    public ScheduleSeatCounts countActive(Long scheduleId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS seats, COUNT(*) FILTER (WHERE is_sellable) AS sellable
                  FROM p_schedule_seats
                 WHERE schedule_id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> new ScheduleSeatCounts(rs.getInt("seats"), rs.getInt("sellable")), scheduleId);
    }

    /**
     * lock_timeout에 걸린 오류(SQLSTATE 55P03)를 CannotAcquireLockException으로 바꾼다. JdbcTemplate의 기본 변환은 이 코드를
     * 분류하지 못해 UncategorizedSQLException으로 올리므로, 컨트롤러가 잠금 실패로 알아보고 503으로 바꿀 수 있게 한다.
     */
    private static <T> T translateLockTimeout(Supplier<T> query) {
        try {
            return query.get();
        } catch (UncategorizedSQLException e) {
            if (e.getSQLException() != null
                    && LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("좌석 준비가 잠금 대기 상한을 넘겼다", e);
            }
            throw e;
        }
    }
}
