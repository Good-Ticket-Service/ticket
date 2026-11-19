package com.sparta.ticket.reservation.seat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.HoldState;
import com.sparta.ticket.reservation.seat.application.port.StoredHold;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis에 연결하지 못하면 선점 저장소가 503 SERVICE_UNAVAILABLE로 거절하는지 확인한다(#449). DB 방식으로 대신하지 않는다.
 * 아무도 듣지 않는 포트로 연결해 실제 연결 실패를 만든다.
 */
class RedisSeatHoldStoreUnavailableTest {

    private LettuceConnectionFactory factory;
    private RedisSeatHoldStore store;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(500))
                        .build());
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisSeatHoldStore(redis);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    private static StoredHold hold() {
        SaleValidation validation = new SaleValidation(
                true,
                null,
                1L,
                100L,
                200L,
                3L,
                4L,
                LocalDateTime.of(2026, 12, 1, 19, 0),
                "공연",
                "MUSICAL",
                List.of(new SaleValidationSeat(501L, 7L, "R", 99_000)));
        return new StoredHold(
                "h_" + "a".repeat(32), 1L, 1L, 11L, validation, Instant.now().plusSeconds(600), HoldState.HELD, null);
    }

    @Test
    @DisplayName("선점·조회·결제하기 시작·해제가 모두 503 SERVICE_UNAVAILABLE이다")
    void unavailable() {
        StoredHold hold = hold();

        assertUnavailable(() -> store.hold(hold, null, Instant.now()));
        assertUnavailable(() -> store.find(1L, hold.holdId()));
        assertUnavailable(() -> store.beginConvert(1L, hold.holdId(), 1L, Instant.now(), Duration.ofSeconds(3)));
        assertUnavailable(() -> store.release(1L, hold.holdId(), 1L));
        assertUnavailable(() -> store.heldSeatIds(1L, Instant.now()));
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
