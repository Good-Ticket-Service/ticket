package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import java.util.function.BiConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * booking 소비자가 함께 쓰는 처리 실패 정책. 토픽마다 같은 정책을 다시 쓰지 않도록 한곳에 둔다.
 *
 * DB 반영을 커밋한 뒤에만 offset을 커밋한다(AckMode.RECORD). 그 사이에 꺼지면 다시 받지만 반영은 한 번만 된다.
 * 해석할 수 없는 메시지만 DLT로 보낸다. 반영 실패는 DB 장애처럼 곧 풀릴 일이고, DLT로 보내면 받은 사실을 잃으므로
 * 간격을 늘려 가며 계속 다시 시도한다. 그동안 이 파티션은 멈추며, 그 신호는 처리 실패 지표와 consumer lag으로 본다.
 */
final class RetryingListenerContainers {

    private static final long RETRY_INITIAL_MS = 1_000;
    private static final long RETRY_MAX_MS = 30_000;

    private RetryingListenerContainers() {}

    static ConcurrentKafkaListenerContainerFactory<String, String> create(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            String deadLetterTopic,
            Class<? extends Exception> notRetryable,
            BiConsumer<ConsumerRecord<?, ?>, Exception> onDeadLetter,
            RetryListener onRetry) {
        ExponentialBackOff retryUntilRecovered = new ExponentialBackOff(RETRY_INITIAL_MS, 2);
        retryUntilRecovered.setMaxInterval(RETRY_MAX_MS);

        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(deadLetterTopic, -1));
        ConsumerAwareRecordRecoverer countedDeadLetters = (record, consumer, ex) -> {
            deadLetters.accept(record, consumer, ex);
            onDeadLetter.accept(record, ex);
        };
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(countedDeadLetters, retryUntilRecovered);
        errorHandler.addNotRetryableExceptions(notRetryable);
        errorHandler.setRetryListeners(onRetry);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        return factory;
    }
}
