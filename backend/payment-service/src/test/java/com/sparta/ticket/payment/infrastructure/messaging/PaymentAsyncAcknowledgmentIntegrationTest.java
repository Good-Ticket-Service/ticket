package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.testcontainers.kafka.KafkaContainer;

/** 승인 Consumer 구현 전에 사용 BOM의 Future 완료와 offset 커밋 경계를 확인한다. */
class PaymentAsyncAcknowledgmentIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void laterCompletionCannotCommitPastAnUnfinishedEarlierRecord() throws Exception {
        try (var broker = new KafkaContainer("apache/kafka:4.3.1")) {
            broker.start();
            String topic = "approval-probe-" + UUID.randomUUID();
            String group = "approval-probe-" + UUID.randomUUID();
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                admin.createTopics(java.util.List.of(new NewTopic(topic, 1, (short) 1)))
                        .all()
                        .get(30, java.util.concurrent.TimeUnit.SECONDS);
                var producer = new DefaultKafkaProducerFactory<String, String>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
                try {
                    var kafka = new KafkaTemplate<>(producer);
                    kafka.send(topic, "same-key", "first").get(10, java.util.concurrent.TimeUnit.SECONDS);
                    kafka.send(topic, "same-key", "second").get(10, java.util.concurrent.TimeUnit.SECONDS);
                    var consumer = new DefaultKafkaConsumerFactory<String, String>(Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            broker.getBootstrapServers(),
                            ConsumerConfig.GROUP_ID_CONFIG,
                            group,
                            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class,
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class,
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            "earliest",
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                            false,
                            ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                            2));
                    var probe = new CompletionProbe();
                    var endpoint = new MethodKafkaListenerEndpoint<String, String>();
                    endpoint.setId(group);
                    endpoint.setGroupId(group);
                    endpoint.setTopics(topic);
                    endpoint.setBean(probe);
                    endpoint.setMethod(CompletionProbe.class.getMethod("receive", String.class));
                    var handler = new DefaultMessageHandlerMethodFactory();
                    handler.afterPropertiesSet();
                    endpoint.setMessageHandlerMethodFactory(handler);
                    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
                    factory.setConsumerFactory(consumer);
                    factory.getContainerProperties().setPollTimeout(100);
                    var container = factory.createListenerContainer(endpoint);
                    try {
                        container.start();
                        await().atMost(WAIT)
                                .untilAsserted(() -> assertThat(probe.pending).hasSize(2));
                        probe.pending.get("second").complete(null);
                        var partition = new TopicPartition(topic, 0);
                        // Note: 후행 완료가 먼저 처리될 시간을 주되, 선행 레코드를 건너뛰는 커밋은 한 번도 허용하지 않는다.
                        await().during(Duration.ofSeconds(1))
                                .atMost(Duration.ofSeconds(3))
                                .untilAsserted(() -> {
                                    var committed = admin.listConsumerGroupOffsets(group)
                                            .partitionsToOffsetAndMetadata()
                                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                                            .get(partition);
                                    assertThat(committed == null || committed.offset() == 0)
                                            .isTrue();
                                });
                        probe.pending.get("first").complete(null);
                        await().atMost(WAIT).untilAsserted(() -> {
                            var committed = admin.listConsumerGroupOffsets(group)
                                    .partitionsToOffsetAndMetadata()
                                    .get(5, java.util.concurrent.TimeUnit.SECONDS)
                                    .get(partition);
                            assertThat(committed).isNotNull();
                            assertThat(committed.offset()).isEqualTo(2);
                        });
                    } finally {
                        container.stop();
                    }
                } finally {
                    producer.destroy();
                }
            }
        }
    }

    public static class CompletionProbe {
        private final Map<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

        public CompletableFuture<Void> receive(String value) {
            var completion = new CompletableFuture<Void>();
            pending.put(value, completion);
            return completion;
        }
    }
}
