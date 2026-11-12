package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.infrastructure.persistence.CompletionPool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox에 남은 메시지를 보내고, 브로커가 받았다고 답한 행만 지운다. 보내지 못한 행은 남아 다음 차례에 다시 보낸다.
 * 보낸 뒤 지우기 전에 꺼지면 같은 메시지를 다시 보낸다. 받는 쪽은 업무 키(orderId)로 중복을 거른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRowRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final BookingMetrics metrics;

    /**
     * 한 인스턴스 안에서는 발행을 한 번에 하나만 돌린다. 커밋 직후 발행과 발행 스케줄러가 함께 돌면 완료 전용 풀 연결을 둘 잡아,
     * 같은 풀을 쓰는 결과 소비자가 연결을 기다리고 결과 반영이 늦어진다. 인스턴스 사이는 SKIP LOCKED가 행을 나눈다.
     */
    private final ReentrantLock turn = new ReentrantLock();

    /**
     * 오래된 행부터 batchSize개를 보내고, 보낸 수를 돌려준다. 행을 잠근 트랜잭션 안에서 보내므로 다른 발행기는 이 행을 건너뛴다.
     * 하나라도 보내지 못하면 거기서 멈춘다. 브로커가 멈췄을 때 남은 행마다 보내기 대기를 되풀이하며 DB 연결을 오래 잡지 않기 위해서다.
     */
    public int publish(int batchSize, Duration sendTimeout) {
        turn.lock();
        try {
            Integer sent = CompletionPool.call(() -> transactionTemplate.execute(status -> {
                List<OutboxRow> rows = outbox.lockOldest(batchSize);
                List<Long> sentIds = new ArrayList<>();
                for (OutboxRow row : rows) {
                    if (!send(row, sendTimeout)) {
                        break;
                    }
                    sentIds.add(row.getId());
                }
                outbox.deleteAllByIdInBatch(sentIds);
                return sentIds.size();
            }));
            int count = sent == null ? 0 : sent;
            if (count > 0) {
                metrics.outboxPublished(count);
            }
            return count;
        } finally {
            turn.unlock();
        }
    }

    private boolean send(OutboxRow row, Duration sendTimeout) {
        try {
            CompletableFuture<SendResult<String, String>> result =
                    kafkaTemplate.send(row.getTopic(), row.getMessageKey(), row.getPayload());
            result.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(row, e);
        } catch (Exception e) {
            return failed(row, e);
        }
    }

    private boolean failed(OutboxRow row, Exception e) {
        metrics.outboxPublishFailed();
        log.warn("Outbox 메시지를 보내지 못했다. 다음 차례에 다시 보낸다. outboxId={}, topic={}", row.getId(), row.getTopic(), e);
        return false;
    }
}
