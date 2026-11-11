package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.testcontainers.kafka.KafkaContainer;

/** 사용 버전의 예외 Future 동작과 명시적 성공 ACK의 차이를 실제 offset으로 고정한다. */
class PaymentConsumerAcknowledgmentBoundaryTest {
    @Test
    void exceptionalFutureAdvancesButManualFailureDoesNotAndRestartRedelivers() throws Exception {
        try (var broker = new KafkaContainer("apache/kafka:4.3.1")) {
            broker.start();
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                var producer = new DefaultKafkaProducerFactory<String, String>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
                try {
                    var kafka = new KafkaTemplate<>(producer);
                    String prefix = "ack-boundary-" + UUID.randomUUID();
                    String unsafe = prefix + "-future";
                    String safe = prefix + "-manual";
                    admin.createTopics(List.of(new NewTopic(unsafe, 1, (short) 1), new NewTopic(safe, 1, (short) 1)))
                            .all()
                            .get(10, TimeUnit.SECONDS);
                    var future = new FutureProbe();
                    var futureContainer = factory(broker.getBootstrapServers(), unsafe)
                            .createListenerContainer(
                                    endpoint(unsafe, future, FutureProbe.class.getMethod("receive", String.class)));
                    try {
                        futureContainer.start();
                        kafka.send(unsafe, "key", "first").get(10, TimeUnit.SECONDS);
                        await().atMost(Duration.ofSeconds(20)).until(() -> future.pending.containsKey("first"));
                        future.pending
                                .get("first")
                                .completeExceptionally(new IllegalStateException("synthetic failure"));
                        await().atMost(Duration.ofSeconds(20))
                                .untilAsserted(
                                        () -> assertThat(offset(admin, unsafe)).isEqualTo(1));
                    } finally {
                        futureContainer.stop();
                    }
                    var manual = new ManualProbe();
                    var container = factory(broker.getBootstrapServers(), safe)
                            .createListenerContainer(endpoint(
                                    safe,
                                    manual,
                                    ManualProbe.class.getMethod("receive", String.class, Acknowledgment.class)));
                    try {
                        container.start();
                        kafka.send(safe, "key", "first").get(10, TimeUnit.SECONDS);
                        kafka.send(safe, "key", "second").get(10, TimeUnit.SECONDS);
                        await().atMost(Duration.ofSeconds(20)).until(() -> manual.pending.size() == 2);
                        manual.pending.get("second").acknowledge();
                        // Note: DB 또는 DLT 송신 실패에서는 선행 ACK를 호출하지 않는다.
                        await().during(Duration.ofSeconds(1))
                                .atMost(Duration.ofSeconds(3))
                                .untilAsserted(
                                        () -> assertThat(offset(admin, safe)).isZero());
                    } finally {
                        container.stop();
                    }
                    var restarted = new ManualProbe();
                    var retry = factory(broker.getBootstrapServers(), safe)
                            .createListenerContainer(endpoint(
                                    safe,
                                    restarted,
                                    ManualProbe.class.getMethod("receive", String.class, Acknowledgment.class)));
                    try {
                        retry.start();
                        await().atMost(Duration.ofSeconds(20)).until(() -> restarted.pending.size() == 2);
                        restarted.pending.get("second").acknowledge();
                        assertThat(offset(admin, safe)).isZero();
                        restarted.pending.get("first").acknowledge();
                        await().atMost(Duration.ofSeconds(20))
                                .untilAsserted(
                                        () -> assertThat(offset(admin, safe)).isEqualTo(2));
                    } finally {
                        retry.stop();
                    }
                } finally {
                    producer.destroy();
                }
            }
        }
    }

    private static long offset(Admin admin, String group) throws Exception {
        var value = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(group, 0));
        return value == null ? 0 : value.offset();
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> factory(String bootstrap, String group) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG,
                group,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                2)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setAsyncAcks(true);
        factory.getContainerProperties().setPollTimeout(100);
        return factory;
    }

    private static MethodKafkaListenerEndpoint<String, String> endpoint(
            String topic, Object bean, java.lang.reflect.Method method) {
        var endpoint = new MethodKafkaListenerEndpoint<String, String>();
        endpoint.setId(topic);
        endpoint.setGroupId(topic);
        endpoint.setTopics(topic);
        endpoint.setBean(bean);
        endpoint.setMethod(method);
        var handler = new DefaultMessageHandlerMethodFactory();
        handler.afterPropertiesSet();
        endpoint.setMessageHandlerMethodFactory(handler);
        return endpoint;
    }

    public static class FutureProbe {
        final Map<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

        public CompletableFuture<Void> receive(String value) {
            var future = new CompletableFuture<Void>();
            pending.put(value, future);
            return future;
        }
    }

    public static class ManualProbe {
        final Map<String, Acknowledgment> pending = new ConcurrentHashMap<>();

        public void receive(String value, Acknowledgment acknowledgment) {
            pending.put(value, acknowledgment);
        }
    }
}
