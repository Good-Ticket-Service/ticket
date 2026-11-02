package com.sparta.ticket.reservation.queue.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.application.dto.QueueEntryResult;
import com.sparta.ticket.reservation.queue.application.dto.QueueStatusResult;
import com.sparta.ticket.reservation.queue.domain.QueueAdmission;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.QueueState;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 공연 단위 대기열. 줄 단위가 회차가 아니라 공연인 이유는 입장 토큰 범위가 공연이고, 회차들이 같은 서버 자원을 쓰기 때문이다.
 * 대기열은 첫 진입 때 생기며 등록 API는 없다.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final WaitingQueue waitingQueue;
    private final QueueProperties properties;
    private final QueueAdmissionProperties admissionProperties;
    private final EntryTokenIssuer entryTokenIssuer;

    /** 줄을 선다. 이미 서 있으면 원래 순번을 돌려준다. */
    public QueueEntryResult enter(long performanceId, long userId) {
        requireEnabled();
        QueuePosition position = waitingQueue.enter(performanceId, userId);
        return new QueueEntryResult(
                position.position(), position.aheadCount(), estimatedWaitSeconds(position.aheadCount()));
    }

    /** 내 순번을 확인한다. 조회할 때마다 마지막 조회 시각이 남아 이탈자 판단에 쓰인다. 입장해 있으면 입장 토큰을 돌려준다. */
    public QueueStatusResult findMyStatus(long performanceId, long userId) {
        requireEnabled();
        QueueState state = waitingQueue
                .find(performanceId, userId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.NOT_IN_QUEUE));
        return toResult(performanceId, userId, state);
    }

    /** 대기를 취소한다. 줄에 없어도 성공으로 본다(같은 요청을 다시 보내도 결과가 같다). */
    public void leave(long performanceId, long userId) {
        requireEnabled();
        waitingQueue.leave(performanceId, userId);
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
    private QueueStatusResult toResult(long performanceId, long userId, QueueState state) {
        if (state instanceof QueueAdmission admission) {
            String token = entryTokenIssuer.issue(
                    userId,
                    performanceId,
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
