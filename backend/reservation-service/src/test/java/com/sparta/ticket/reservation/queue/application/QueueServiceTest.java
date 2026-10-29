package com.sparta.ticket.reservation.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.application.dto.QueueEntryResult;
import com.sparta.ticket.reservation.queue.application.dto.QueueStatusResult;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.QueueStatus;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueServiceTest {

    /** 정해 둔 앞 인원을 돌려주고 호출 횟수를 센다. inQueue가 false면 순번 조회에서 줄에 없는 회원이 된다. */
    private static final class FixedWaitingQueue implements WaitingQueue {

        private final long aheadCount;
        private final boolean inQueue;
        private final AtomicInteger calls = new AtomicInteger();

        FixedWaitingQueue(long aheadCount) {
            this(aheadCount, true);
        }

        FixedWaitingQueue(long aheadCount, boolean inQueue) {
            this.aheadCount = aheadCount;
            this.inQueue = inQueue;
        }

        @Override
        public QueuePosition enter(long performanceId, long userId) {
            calls.incrementAndGet();
            return new QueuePosition(aheadCount);
        }

        @Override
        public Optional<QueuePosition> findPosition(long performanceId, long userId) {
            calls.incrementAndGet();
            return inQueue ? Optional.of(new QueuePosition(aheadCount)) : Optional.empty();
        }
    }

    @Test
    @DisplayName("대기열이 꺼져 있으면 줄을 세우지 않고 503 QUEUE_DISABLED")
    void disabled() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(0);
        QueueService service = new QueueService(waitingQueue, new QueueProperties(false, 10));

        assertThatThrownBy(() -> service.enter(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.QUEUE_DISABLED);
        assertThat(waitingQueue.calls).hasValue(0);
    }

    @Test
    @DisplayName("예상 대기 시간은 앞 인원 ÷ 초당 입장 인원을 올림한 초다")
    void estimatedWaitSeconds() {
        QueueProperties properties = new QueueProperties(true, 10);

        assertThat(new QueueService(new FixedWaitingQueue(0), properties).enter(1L, 7L))
                .isEqualTo(new QueueEntryResult(1, 0, 0));
        assertThat(new QueueService(new FixedWaitingQueue(10), properties).enter(1L, 7L))
                .isEqualTo(new QueueEntryResult(11, 10, 1));
        assertThat(new QueueService(new FixedWaitingQueue(11), properties).enter(1L, 7L))
                .isEqualTo(new QueueEntryResult(12, 11, 2));
    }

    @Test
    @DisplayName("순번 조회는 WAITING과 순번, 앞 인원, 예상 대기 시간을 돌려준다")
    void findMyStatus() {
        QueueService service = new QueueService(new FixedWaitingQueue(10), new QueueProperties(true, 10));

        assertThat(service.findMyStatus(1L, 7L)).isEqualTo(new QueueStatusResult(QueueStatus.WAITING, 11, 10, 1));
    }

    @Test
    @DisplayName("줄에 없으면 순번 조회는 404 NOT_IN_QUEUE")
    void findMyStatusNotInQueue() {
        QueueService service = new QueueService(new FixedWaitingQueue(0, false), new QueueProperties(true, 10));

        assertThatThrownBy(() -> service.findMyStatus(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.NOT_IN_QUEUE);
    }

    @Test
    @DisplayName("대기열이 꺼져 있으면 순번을 조회하지 않고 503 QUEUE_DISABLED")
    void findMyStatusDisabled() {
        FixedWaitingQueue waitingQueue = new FixedWaitingQueue(0);
        QueueService service = new QueueService(waitingQueue, new QueueProperties(false, 10));

        assertThatThrownBy(() -> service.findMyStatus(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(QueueErrorCode.QUEUE_DISABLED);
        assertThat(waitingQueue.calls).hasValue(0);
    }
}
