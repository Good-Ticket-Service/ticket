package com.sparta.ticket.reservation.queue.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.application.dto.QueueStatusResult;
import com.sparta.ticket.reservation.queue.domain.QueueAdmission;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.QueueState;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import com.sparta.ticket.reservation.queue.domain.WaitingRoom;
import com.sparta.ticket.reservation.queue.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 대기열(room)의 줄 서기·순번 조회·대기 취소. room은 관리자가 미리 만들고, 줄은 room마다 하나다.
 * 오픈 전·마감 판단은 room에 상태를 저장하지 않고 room의 openAt·closeAt과 서버 시각을 비교한다.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final WaitingQueue waitingQueue;
    private final QueueProperties properties;
    private final QueueAdmissionProperties admissionProperties;
    private final EntryTokenIssuer entryTokenIssuer;
    private final WaitingRoomRepository waitingRoomRepository;
    private final Clock clock;

    /**
     * 줄을 선다. 이미 서 있으면 원래 순번을, 입장해 있으면 줄에 다시 세우지 않고 입장 토큰을 돌려준다.
     * room이 없으면 404 WAITING_ROOM_NOT_FOUND, 오픈 전이면 409 WAITING_ROOM_NOT_OPEN, 끝났으면 409 WAITING_ROOM_CLOSED다.
     */
    public QueueStatusResult enter(long roomId, long userId) {
        requireEnabled();
        WaitingRoom room = findRoom(roomId);
        room.verifyEnterable(clock.instant());
        return toResult(room, userId, waitingQueue.enter(roomId, userId));
    }

    /**
     * 내 순번을 확인한다. 조회할 때마다 마지막 조회 시각이 남아 이탈자 판단에 쓰인다. 입장해 있으면 입장 토큰을 돌려준다.
     * room이 끝났으면 줄에 있어도 409 WAITING_ROOM_CLOSED다. 프런트는 이때 대기열 없이 좌석 화면으로 보낸다.
     */
    public QueueStatusResult findMyStatus(long roomId, long userId) {
        requireEnabled();
        WaitingRoom room = findRoom(roomId);
        room.verifyNotClosed(clock.instant());
        QueueState state =
                waitingQueue.find(roomId, userId).orElseThrow(() -> new BusinessException(QueueErrorCode.NOT_IN_QUEUE));
        return toResult(room, userId, state);
    }

    /** 대기를 취소한다. 줄에 없어도, room이 없거나 끝났어도 성공으로 본다(같은 요청을 다시 보내도 결과가 같다). */
    public void leave(long roomId, long userId) {
        requireEnabled();
        waitingQueue.leave(roomId, userId);
    }

    /** room은 생성 뒤 바뀌지 않아 저장소가 메모리에 캐시해 두므로, 폴링마다 Redis를 읽지 않는다. */
    private WaitingRoom findRoom(long roomId) {
        return waitingRoomRepository
                .findById(roomId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.WAITING_ROOM_NOT_FOUND));
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BusinessException(QueueErrorCode.QUEUE_DISABLED);
        }
    }

    /**
     * 입장 시각은 따로 저장하지 않고 만료 시각 − 입장 유효 시간으로 구한다. 입장 목록에는 만료 시각만 있기 때문이다.
     * 토큰은 저장하지 않고 조회할 때마다 같은 값으로 다시 서명한다.
     */
    private QueueStatusResult toResult(WaitingRoom room, long userId, QueueState state) {
        if (state instanceof QueueAdmission admission) {
            String token = entryTokenIssuer.issue(
                    userId,
                    room.roomId(),
                    room.scopes(),
                    admission.expiresAt().minus(admissionProperties.entryTtl()),
                    admission.expiresAt());
            return QueueStatusResult.entered(token, admission.expiresAt().atOffset(KST));
        }
        QueuePosition position = (QueuePosition) state;
        return QueueStatusResult.waiting(
                position.position(), position.aheadCount(), estimatedWaitSeconds(position.aheadCount()));
    }

    /** 앞 인원이 초당 입장 인원만큼씩 빠진다고 보고 올림한다. 실제 입장 속도는 입장 스케줄러가 같은 설정값으로 지킨다. */
    private long estimatedWaitSeconds(long aheadCount) {
        int admitPerSecond = properties.admitPerSecond();
        return (aheadCount + admitPerSecond - 1) / admitPerSecond;
    }
}
