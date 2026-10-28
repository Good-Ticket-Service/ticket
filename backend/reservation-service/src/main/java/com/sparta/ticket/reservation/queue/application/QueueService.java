package com.sparta.ticket.reservation.queue.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.application.dto.QueueEntryResult;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 공연 단위 대기열. 줄 단위가 회차가 아니라 공연인 이유는 입장 토큰 범위가 공연이고, 회차들이 같은 서버 자원을 쓰기 때문이다.
 * 대기열은 첫 진입 때 생기며 등록 API는 없다.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    private final WaitingQueue waitingQueue;
    private final QueueProperties properties;

    /** 줄을 선다. 이미 서 있으면 원래 순번을 돌려준다. */
    public QueueEntryResult enter(long performanceId, long userId) {
        requireEnabled();
        QueuePosition position = waitingQueue.enter(performanceId, userId);
        return new QueueEntryResult(
                position.position(), position.aheadCount(), estimatedWaitSeconds(position.aheadCount()));
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BusinessException(QueueErrorCode.QUEUE_DISABLED);
        }
    }

    /** 앞 인원이 초당 입장 인원만큼씩 빠진다고 보고 올림한다. 실제 입장 속도는 입장 스케줄러가 같은 설정값으로 지킨다. */
    private long estimatedWaitSeconds(long aheadCount) {
        int admitPerSecond = properties.admitPerSecond();
        return (aheadCount + admitPerSecond - 1) / admitPerSecond;
    }
}
