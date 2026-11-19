package com.sparta.ticket.reservation.seat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.ConvertOutcome;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldAttempt;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldOutcome;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldState;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.ReleaseOutcome;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 선점 Lua 스크립트를 실제 Redis로 확인한다(#449). 회차 ID는 테스트마다 새로 써서 다른 테스트의 키와 섞이지 않게 한다. */
class RedisSeatHoldStoreIntegrationTest extends ReservationIntegrationTest {

    /** DB 회차와 겹치지 않는 큰 ID부터 쓴다. 이 테스트는 DB를 쓰지 않는다. */
    private static final AtomicLong IDS = new AtomicLong(8_000_000_000L + System.nanoTime() % 1_000_000);

    private static final Long SEAT = 11L;

    private static final Long OTHER_SEAT = 12L;

    /** 같은 좌석 동시 선점에 함께 출발하는 회원 수. 풀 크기보다 많으면 동시에 출발하지 못한다. */
    private static final int RACERS = 8;

    private static final int ROUNDS = 20;

    @Autowired
    private SeatHoldStore store;

    @Autowired
    private StringRedisTemplate redis;

    private final ExecutorService pool = Executors.newFixedThreadPool(RACERS);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static long newSchedule() {
        return IDS.incrementAndGet();
    }

    private static StoredHold newHold(long scheduleId, Long userId, Long seatId, Instant expiresAt) {
        SaleValidation validation = new SaleValidation(
                true,
                null,
                scheduleId,
                100L,
                200L,
                3L,
                4L,
                LocalDateTime.of(2026, 12, 1, 19, 0),
                "공연",
                "MUSICAL",
                List.of(new SaleValidationSeat(500L + seatId, 7L, "R", 99_000)));
        String holdId = "h_" + UUID.randomUUID().toString().replace("-", "");
        return new StoredHold(holdId, scheduleId, userId, seatId, validation, expiresAt, HoldState.HELD, null);
    }

    private static Instant inMinutes(long minutes) {
        return Instant.now().plus(Duration.ofMinutes(minutes));
    }

    /**
     * 키가 사라질 절대 시각(Redis 시계, epoch ms). PEXPIRETIME은 Redis 7.0부터 있다.
     * Note: 드라이버가 결과 형식을 모르는 명령이라 Lua로 감싸 숫자로 받는다.
     */
    private long expireTimeMillis(String key) {
        return redis.execute(
                new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key));
    }

    private Long convert(StoredHold hold, long reservationId) {
        assertThat(store.beginConvert(
                                hold.scheduleId(), hold.holdId(), hold.userId(), Instant.now(), Duration.ofSeconds(3))
                        .outcome())
                .isEqualTo(ConvertOutcome.STARTED);
        store.finishConvert(hold, reservationId, inMinutes(60), Instant.now());
        return reservationId;
    }

    @Nested
    @DisplayName("선점")
    class Hold {

        @Test
        @DisplayName("비어 있으면 기록하고, 저장한 값을 그대로 다시 읽는다")
        void createsAndReadsBack() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));

            HoldAttempt attempt = store.hold(hold, null, Instant.now());

            assertThat(attempt.outcome()).isEqualTo(HoldOutcome.CREATED);
            assertThat(attempt.holdId()).isEqualTo(hold.holdId());
            StoredHold read = store.find(schedule, hold.holdId()).orElseThrow();
            assertThat(read)
                    .usingRecursiveComparison()
                    .withComparatorForType((a, b) -> Long.compare(a.toEpochMilli(), b.toEpochMilli()), Instant.class)
                    .isEqualTo(hold);
            assertThat(redis.getExpire(SeatHoldKeys.seat(schedule, SEAT), TimeUnit.SECONDS))
                    .isBetween(590L, 600L);
            assertThat(redis.opsForZSet().score(SeatHoldKeys.held(schedule), SEAT.toString()))
                    .isEqualTo((double) hold.expiresAt().toEpochMilli());
        }

        @Test
        @DisplayName("같은 회원이 같은 좌석을 다시 누르면 기존 선점을 돌려준다")
        void sameUserSameSeat() {
            long schedule = newSchedule();
            StoredHold first = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(first, null, Instant.now());

            HoldAttempt again = store.hold(newHold(schedule, 1L, SEAT, inMinutes(10)), null, Instant.now());

            assertThat(again.outcome()).isEqualTo(HoldOutcome.EXISTING);
            assertThat(again.holdId()).isEqualTo(first.holdId());
        }

        @Test
        @DisplayName("다른 회원이 잡은 좌석은 TAKEN, 다른 좌석을 잡은 회원은 LIMIT이다")
        void takenAndLimit() {
            long schedule = newSchedule();
            store.hold(newHold(schedule, 1L, SEAT, inMinutes(10)), null, Instant.now());

            assertThat(store.hold(newHold(schedule, 2L, SEAT, inMinutes(10)), null, Instant.now())
                            .outcome())
                    .isEqualTo(HoldOutcome.TAKEN);
            assertThat(store.hold(newHold(schedule, 1L, OTHER_SEAT, inMinutes(10)), null, Instant.now())
                            .outcome())
                    .isEqualTo(HoldOutcome.LIMIT);
        }

        @Test
        @DisplayName("예약으로 바꾼 회원은 좌석과 관계없이 CONVERTED_USER와 예약 ID다")
        void convertedUser() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());
            convert(hold, 77L);

            HoldAttempt again = store.hold(newHold(schedule, 1L, OTHER_SEAT, inMinutes(10)), null, Instant.now());

            assertThat(again.outcome()).isEqualTo(HoldOutcome.CONVERTED_USER);
            assertThat(again.reservationId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("키 수명은 앱 시계 기준 상대 시간이라, 앱 시계가 Redis와 1시간 달라도 정한 유지 시간만큼 산다")
        void ttlIsRelativeToAppClock() {
            long schedule = newSchedule();
            Instant appNow = Instant.now().minus(Duration.ofHours(1));
            StoredHold hold = newHold(schedule, 1L, SEAT, appNow.plus(Duration.ofMinutes(10)));

            assertThat(store.hold(hold, null, appNow).outcome()).isEqualTo(HoldOutcome.CREATED);

            // 절대 시각(PXAT)으로 넘겼다면 Redis 시계로는 50분 전이라 곧바로 사라졌다
            for (String key : List.of(
                    SeatHoldKeys.seat(schedule, SEAT),
                    SeatHoldKeys.user(schedule, 1L),
                    SeatHoldKeys.hold(schedule, hold.holdId()))) {
                assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(590_000L, 600_000L);
            }
        }

        @Test
        @DisplayName("키는 앱이 정한 만료 시각보다 일찍 사라지지 않고, 늦어도 호출이 Redis에 닿기까지의 시간 안이다")
        void expiresNotBeforeAppDeadline() {
            long schedule = newSchedule();
            Instant now = Instant.now();
            StoredHold hold = newHold(schedule, 1L, SEAT, now.plus(Duration.ofMinutes(10)));

            store.hold(hold, null, now);
            long elapsed = System.currentTimeMillis() - now.toEpochMilli();

            // 테스트 컨테이너는 같은 PC의 시계를 써서 Redis의 절대 만료 시각과 앱의 만료 시각을 비교할 수 있다
            for (String key : List.of(
                    SeatHoldKeys.seat(schedule, SEAT),
                    SeatHoldKeys.user(schedule, 1L),
                    SeatHoldKeys.hold(schedule, hold.holdId()))) {
                long late = expireTimeMillis(key) - hold.expiresAt().toEpochMilli();
                assertThat(late).isBetween(0L, elapsed);
            }
        }

        @Test
        @DisplayName("DB가 잡은 좌석은 그 예약을 바꾼 회원이면 MINE_IN_DB, 아니면 TAKEN이다")
        void heldInDb() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());
            convert(hold, 77L);

            HoldAttempt mine = store.hold(newHold(schedule, 1L, SEAT, inMinutes(10)), 77L, Instant.now());
            assertThat(mine.outcome()).isEqualTo(HoldOutcome.MINE_IN_DB);
            assertThat(mine.reservationId()).isEqualTo(77L);

            assertThat(store.hold(newHold(schedule, 2L, SEAT, inMinutes(10)), 77L, Instant.now())
                            .outcome())
                    .isEqualTo(HoldOutcome.TAKEN);
            assertThat(redis.hasKey(SeatHoldKeys.user(schedule, 2L))).isFalse();
        }

        @Test
        @DisplayName("만료 시각이 지난 선점 좌석은 목록에서 치운다")
        void prunesExpiredFromHeld() {
            long schedule = newSchedule();
            redis.opsForZSet()
                    .add(
                            SeatHoldKeys.held(schedule),
                            "99",
                            Instant.now().minusSeconds(1).toEpochMilli());

            store.hold(newHold(schedule, 1L, SEAT, inMinutes(10)), null, Instant.now());

            assertThat(redis.opsForZSet().range(SeatHoldKeys.held(schedule), 0, -1))
                    .containsExactly(SEAT.toString());
        }

        @Test
        @DisplayName("8명이 같은 좌석을 동시에 선점하면 매번 1명만 기록된다(20번 반복)")
        void sameSeatRace() throws Exception {
            for (int round = 0; round < ROUNDS; round++) {
                long schedule = newSchedule();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<HoldAttempt>> futures = new ArrayList<>();
                List<StoredHold> holds = new ArrayList<>();
                for (long user = 1; user <= RACERS; user++) {
                    StoredHold hold = newHold(schedule, user, SEAT, inMinutes(10));
                    holds.add(hold);
                    futures.add(pool.submit(() -> {
                        start.await();
                        return store.hold(hold, null, Instant.now());
                    }));
                }
                start.countDown();
                List<HoldAttempt> results = new ArrayList<>();
                for (Future<HoldAttempt> future : futures) {
                    results.add(future.get(10, TimeUnit.SECONDS));
                }

                List<HoldAttempt> created = results.stream()
                        .filter(result -> result.outcome() == HoldOutcome.CREATED)
                        .toList();
                assertThat(created).hasSize(1);
                assertThat(results)
                        .filteredOn(result -> result.outcome() == HoldOutcome.TAKEN)
                        .hasSize(RACERS - 1);
                String winner = created.get(0).holdId();
                assertThat(redis.opsForValue().get(SeatHoldKeys.seat(schedule, SEAT)))
                        .isEqualTo(winner);
                for (StoredHold hold : holds) {
                    boolean won = hold.holdId().equals(winner);
                    assertThat(redis.hasKey(SeatHoldKeys.user(schedule, hold.userId())))
                            .isEqualTo(won);
                    assertThat(redis.hasKey(SeatHoldKeys.hold(schedule, hold.holdId())))
                            .isEqualTo(won);
                }
            }
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        @Test
        @DisplayName("본인 선점을 지우면 좌석·회원 키가 비어 다른 회원이 잡을 수 있다")
        void releasesOwnHold() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());

            assertThat(store.release(schedule, hold.holdId(), 1L).outcome()).isEqualTo(ReleaseOutcome.RELEASED);

            assertThat(store.find(schedule, hold.holdId())).isEmpty();
            assertThat(redis.opsForZSet().size(SeatHoldKeys.held(schedule))).isZero();
            assertThat(store.hold(newHold(schedule, 2L, SEAT, inMinutes(10)), null, Instant.now())
                            .outcome())
                    .isEqualTo(HoldOutcome.CREATED);
            assertThat(store.hold(newHold(schedule, 1L, OTHER_SEAT, inMinutes(10)), null, Instant.now())
                            .outcome())
                    .isEqualTo(HoldOutcome.CREATED);
        }

        @Test
        @DisplayName("없는 선점은 GONE, 남의 선점은 FORBIDDEN, 바꾸는 중이면 BUSY, 바뀐 뒤면 예약 ID다")
        void otherOutcomes() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());

            assertThat(store.release(schedule, "h_none", 1L).outcome()).isEqualTo(ReleaseOutcome.GONE);
            assertThat(store.release(schedule, hold.holdId(), 2L).outcome()).isEqualTo(ReleaseOutcome.FORBIDDEN);

            store.beginConvert(schedule, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3));
            assertThat(store.release(schedule, hold.holdId(), 1L).outcome()).isEqualTo(ReleaseOutcome.BUSY);

            store.finishConvert(hold, 77L, inMinutes(60), Instant.now());
            SeatHoldStore.ReleaseAttempt converted = store.release(schedule, hold.holdId(), 1L);
            assertThat(converted.outcome()).isEqualTo(ReleaseOutcome.CONVERTED);
            assertThat(converted.reservationId()).isEqualTo(77L);
        }
    }

    @Nested
    @DisplayName("예약으로 바꾸기")
    class Convert {

        @Test
        @DisplayName("완료하면 좌석 키를 지우고 회원 키는 CONVERTED로 바꿔 늘린 만료까지 둔다")
        void finish() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());

            convert(hold, 77L);

            assertThat(redis.hasKey(SeatHoldKeys.seat(schedule, SEAT))).isFalse();
            assertThat(redis.opsForZSet().size(SeatHoldKeys.held(schedule))).isZero();
            assertThat(redis.opsForValue().get(SeatHoldKeys.user(schedule, 1L))).isEqualTo("CONVERTED:77");
            assertThat(redis.getExpire(SeatHoldKeys.user(schedule, 1L), TimeUnit.MINUTES))
                    .isBetween(58L, 60L);
            StoredHold read = store.find(schedule, hold.holdId()).orElseThrow();
            assertThat(read.state()).isEqualTo(HoldState.CONVERTED);
            assertThat(read.reservationId()).isEqualTo(77L);
            // 좌석은 이제 DB가 판정하므로 다른 회원이 Redis로는 잡을 수 있다. DB 좌석 행이 막는다
            assertThat(store.hold(newHold(schedule, 2L, SEAT, inMinutes(10)), null, Instant.now())
                            .outcome())
                    .isEqualTo(HoldOutcome.CREATED);
        }

        @Test
        @DisplayName("이미 바뀐 선점을 다시 시작하면 예약 ID, 바꾸는 중이면 다시 STARTED다")
        void beginAgain() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());

            assertThat(store.beginConvert(schedule, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3))
                            .outcome())
                    .isEqualTo(ConvertOutcome.STARTED);
            assertThat(store.beginConvert(schedule, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3))
                            .outcome())
                    .isEqualTo(ConvertOutcome.STARTED);

            store.finishConvert(hold, 77L, inMinutes(60), Instant.now());
            SeatHoldStore.ConvertStart again =
                    store.beginConvert(schedule, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3));
            assertThat(again.outcome()).isEqualTo(ConvertOutcome.CONVERTED);
            assertThat(again.reservationId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("없는 선점은 NOT_FOUND, 남의 선점은 FORBIDDEN, 남은 시간이 모자라면 EXPIRED다")
        void beginRejected() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, Instant.now().plusSeconds(2));
            store.hold(hold, null, Instant.now());

            assertThat(store.beginConvert(schedule, "h_none", 1L, Instant.now(), Duration.ofSeconds(3))
                            .outcome())
                    .isEqualTo(ConvertOutcome.NOT_FOUND);
            assertThat(store.beginConvert(schedule, hold.holdId(), 2L, Instant.now(), Duration.ofSeconds(3))
                            .outcome())
                    .isEqualTo(ConvertOutcome.FORBIDDEN);
            assertThat(store.beginConvert(schedule, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3))
                            .outcome())
                    .isEqualTo(ConvertOutcome.EXPIRED);
        }

        @Test
        @DisplayName("남은 시간은 키 수명이 아니라 저장한 만료 시각을 앱 시계로 비교한다")
        void remainingByAppClock() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());
            Instant appNow = hold.expiresAt().minusSeconds(2);

            // Redis 키는 10분 가까이 남았지만, 앱 시계로는 2초 남아 3초 기준에 모자란다
            assertThat(store.beginConvert(schedule, hold.holdId(), 1L, appNow, Duration.ofSeconds(3))
                            .outcome())
                    .isEqualTo(ConvertOutcome.EXPIRED);
            assertThat(redis.getExpire(SeatHoldKeys.hold(schedule, hold.holdId()), TimeUnit.MINUTES))
                    .isGreaterThanOrEqualTo(9L);
        }

        @Test
        @DisplayName("revert는 다시 HELD로 되돌리고, drop은 선점을 지운다")
        void abort() {
            long schedule = newSchedule();
            StoredHold hold = newHold(schedule, 1L, SEAT, inMinutes(10));
            store.hold(hold, null, Instant.now());
            store.beginConvert(schedule, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3));

            store.abortConvert(hold, false);
            assertThat(store.find(schedule, hold.holdId()).orElseThrow().state())
                    .isEqualTo(HoldState.HELD);

            store.beginConvert(schedule, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3));
            store.abortConvert(hold, true);
            assertThat(store.find(schedule, hold.holdId())).isEmpty();
            assertThat(redis.hasKey(SeatHoldKeys.seat(schedule, SEAT))).isFalse();
            assertThat(redis.hasKey(SeatHoldKeys.user(schedule, 1L))).isFalse();
        }
    }

    @Nested
    @DisplayName("끝난 예약의 회원 표시 지우기")
    class ClearConvertedUser {

        @Test
        @DisplayName("값이 같을 때만 지우고, 그 사이 새 예약으로 바뀌었으면 그대로 둔다")
        void comparesBeforeDelete() {
            long schedule = newSchedule();
            redis.opsForValue().set(SeatHoldKeys.user(schedule, 1L), "CONVERTED:78");

            assertThat(store.clearConvertedUser(schedule, 1L, 77L)).isFalse();
            assertThat(redis.opsForValue().get(SeatHoldKeys.user(schedule, 1L))).isEqualTo("CONVERTED:78");

            assertThat(store.clearConvertedUser(schedule, 1L, 78L)).isTrue();
            assertThat(redis.hasKey(SeatHoldKeys.user(schedule, 1L))).isFalse();
        }
    }
}
