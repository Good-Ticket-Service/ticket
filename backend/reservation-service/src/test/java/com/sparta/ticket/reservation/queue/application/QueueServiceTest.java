package com.sparta.ticket.reservation.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.application.dto.QueueEntryResult;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.QueuePosition;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueServiceTest {

    /** 정해 둔 앞 인원을 돌려주고 호출 횟수를 센다. */
    private static final class FixedWaitingQueue implements WaitingQueue {

        private final long aheadCount;
        private final AtomicInteger calls = new AtomicInteger();

        FixedWaitingQueue(long aheadCount) {
            this.aheadCount = aheadCount;
        }

        @Override
        public QueuePosition enter(long performanceId, long userId) {
            calls.incrementAndGet();
            return new QueuePosition(aheadCount);
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
}
