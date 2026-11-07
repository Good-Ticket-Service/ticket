package com.sparta.ticket.reservation.queue.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.WaitingRoom;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * room 저장소를 실제 Redis 7.4로 확인한다. Redis는 테스트끼리 공유하므로 테스트마다 다른 회차 번호로 scope를 만든다.
 */
class RedisWaitingRoomRepositoryIntegrationTest extends ReservationIntegrationTest {

    private static final AtomicLong SCHEDULE_IDS = new AtomicLong(5_000_000);

    @Autowired
    private RedisWaitingRoomRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    private static String newScope() {
        return "schedule:" + SCHEDULE_IDS.incrementAndGet();
    }

    /** Redis에 ms 단위로 저장하므로 비교할 시각도 ms로 자른다. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private WaitingRoom create(List<String> scopes, Instant openAt, Instant closeAt) {
        return repository.create(scopes, openAt, closeAt, closeAt.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("만든 room을 ID와 scope로 찾는다")
    void createAndFind() {
        String scope1 = newScope();
        String scope2 = newScope();
        Instant openAt = now().plus(Duration.ofMinutes(5));
        Instant closeAt = openAt.plus(Duration.ofHours(2));

        WaitingRoom created = create(List.of(scope1, scope2), openAt, closeAt);

        assertThat(repository.findById(created.roomId())).contains(created);
        assertThat(repository.findByScope(scope2)).contains(created);
        assertThat(repository.findByScope(newScope())).isEmpty();
        assertThat(repository.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("room 설정은 closeAt + 입장 유효 시간에, scope 키는 closeAt에 만료된다")
    void expiry() {
        String scope = newScope();
        Instant closeAt = now().plus(Duration.ofHours(1));

        WaitingRoom room = create(List.of(scope), now(), closeAt);

        long configExpireAt = redis.getExpire(QueueKeys.roomConfig(room.roomId()), TimeUnit.MILLISECONDS)
                + System.currentTimeMillis();
        long scopeExpireAt =
                redis.getExpire(QueueKeys.scope(scope), TimeUnit.MILLISECONDS) + System.currentTimeMillis();
        assertThat(configExpireAt)
                .isCloseTo(closeAt.plus(Duration.ofMinutes(10)).toEpochMilli(), offset(1_000L));
        assertThat(scopeExpireAt).isCloseTo(closeAt.toEpochMilli(), offset(1_000L));
    }

    @Test
    @DisplayName("끝나지 않은 room과 scope가 하나라도 겹치면 409 WAITING_ROOM_SCOPE_CONFLICT이고 아무것도 쓰지 않는다")
    void scopeConflict() {
        String shared = newScope();
        String fresh = newScope();
        Instant closeAt = now().plus(Duration.ofHours(1));
        create(List.of(shared), now(), closeAt);

        assertThatThrownBy(() -> create(List.of(fresh, shared), now(), closeAt))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.WAITING_ROOM_SCOPE_CONFLICT);
        assertThat(repository.findByScope(fresh)).isEmpty();
    }

    @Test
    @DisplayName("closeAt이 지나면 scope로는 찾지 못하고(ID로는 찾음) 같은 scope로 새 room을 만들 수 있다")
    void reuseScopeAfterClose() throws InterruptedException {
        String scope = newScope();
        WaitingRoom ended = create(List.of(scope), now(), now().plusMillis(200));
        Thread.sleep(300);
        assertThat(repository.findByScope(scope)).isEmpty();
        assertThat(repository.findById(ended.roomId())).contains(ended);

        WaitingRoom next = create(List.of(scope), now(), now().plus(Duration.ofHours(1)));

        assertThat(repository.findByScope(scope)).contains(next);
    }

    @Test
    @DisplayName("끝나지 않은 room만 목록에 나오고(오픈 전 포함), closeAt이 지나면 목록에서 저절로 빠진다")
    void findActive() throws InterruptedException {
        Instant now = now();
        WaitingRoom endingSoon = create(List.of(newScope()), now, now.plusMillis(200));
        WaitingRoom notOpenYet =
                create(List.of(newScope()), now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(3)));

        assertThat(repository.findActive()).contains(endingSoon, notOpenYet);

        Thread.sleep(300);
        assertThat(repository.findActive()).contains(notOpenYet).doesNotContain(endingSoon);
    }
}
