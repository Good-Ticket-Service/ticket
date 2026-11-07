package com.sparta.ticket.reservation.queue.infrastructure.redis;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.WaitingRoom;
import com.sparta.ticket.reservation.queue.domain.WaitingRoomRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis로 구현한 room 저장소. room은 생성 뒤 바뀌지 않으므로 수정 명령이 없다.
 *
 * Note: roomId는 스크립트 밖에서 INCR로 먼저 받는다. 스크립트가 쓰는 키(room 설정)를 KEYS로 미리 넘기기 위해서다.
 * scope가 겹쳐 만들지 못하면 받은 번호는 버려진다. 번호에 빈칸이 생길 뿐 무해하다.
 */
@Component
public class RedisWaitingRoomRepository implements WaitingRoomRepository {

    private static final RedisScript<Long> CREATE_SCRIPT = loadScript("queue/create-room.lua");

    /** Hash에 scopes를 한 값으로 담을 때 쓰는 구분자. scope 형식(예: schedule:12)에 쉼표가 없다. */
    private static final String SCOPE_DELIMITER = ",";

    private static final String SCOPES = "scopes";
    private static final String OPEN_AT = "openAt";
    private static final String CLOSE_AT = "closeAt";

    private final StringRedisTemplate redis;

    public RedisWaitingRoomRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public WaitingRoom create(List<String> scopes, Instant openAt, Instant closeAt, Instant keepUntil) {
        long roomId = call(() -> redis.opsForValue().increment(QueueKeys.ROOM_SEQ));
        List<String> keys = new ArrayList<>();
        keys.add(QueueKeys.roomConfig(roomId));
        keys.add(QueueKeys.ACTIVE_ROOMS);
        scopes.forEach(scope -> keys.add(QueueKeys.scope(scope)));
        Long created = call(() -> redis.execute(
                CREATE_SCRIPT,
                keys,
                String.valueOf(roomId),
                String.join(SCOPE_DELIMITER, scopes),
                String.valueOf(openAt.toEpochMilli()),
                String.valueOf(closeAt.toEpochMilli()),
                String.valueOf(keepUntil.toEpochMilli())));
        if (created == null || created == 0) {
            throw new BusinessException(QueueErrorCode.WAITING_ROOM_SCOPE_CONFLICT);
        }
        return new WaitingRoom(roomId, scopes, openAt, closeAt);
    }

    @Override
    public Optional<WaitingRoom> findById(long roomId) {
        Map<Object, Object> fields = call(() -> redis.opsForHash().entries(QueueKeys.roomConfig(roomId)));
        return toRoom(roomId, fields);
    }

    @Override
    public Optional<WaitingRoom> findByScope(String scope) {
        String roomId = call(() -> redis.opsForValue().get(QueueKeys.scope(scope)));
        if (roomId == null) {
            return Optional.empty();
        }
        return findById(Long.parseLong(roomId));
    }

    /**
     * 목록 필드는 closeAt에 만료되므로 남은 roomId가 곧 끝나지 않은 room이다.
     * room 수만큼 설정을 하나씩 읽는다. room은 생성 뒤 바뀌지 않으므로 반복 호출은 room 캐시로 줄인다.
     */
    @Override
    public List<WaitingRoom> findActive() {
        Set<Object> roomIds = call(() -> redis.opsForHash().keys(QueueKeys.ACTIVE_ROOMS));
        return roomIds.stream()
                .map(id -> findById(Long.parseLong((String) id)))
                .flatMap(Optional::stream)
                .toList();
    }

    /** 설정이 만료돼 비어 있으면 room이 없는 것이다. */
    private static Optional<WaitingRoom> toRoom(long roomId, Map<Object, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WaitingRoom(
                roomId,
                Arrays.asList(((String) fields.get(SCOPES)).split(SCOPE_DELIMITER)),
                Instant.ofEpochMilli(Long.parseLong((String) fields.get(OPEN_AT))),
                Instant.ofEpochMilli(Long.parseLong((String) fields.get(CLOSE_AT)))));
    }

    /** Redis에 연결하지 못하면 503으로 거절한다(fail-closed). */
    private static <T> T call(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException e) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static RedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }
}
