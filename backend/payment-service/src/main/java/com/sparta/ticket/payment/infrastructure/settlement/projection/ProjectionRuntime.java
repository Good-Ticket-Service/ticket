package com.sparta.ticket.payment.infrastructure.settlement.projection;

import com.sparta.ticket.payment.application.port.settlement.ProjectionBatchApplier;
import com.sparta.ticket.payment.domain.model.settlement.projection.ProjectionResult;
import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionCatalogJdbc;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionDbWorker;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.ProjectionSinkJdbc;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** source TX·발행 TX·Kafka 처리·sink TX의 경계를 분리한다. */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "payment.settlement.projection",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProjectionRuntime implements AutoCloseable {
    private final ProjectionProperties config;
    private final ProjectionCatalogJdbc catalog;
    private final ProjectionBatchApplier baseline;
    private final ProjectionSinkJdbc sink;
    private final String bootstrap;
    private final ProjectionCheckpoint checkpoint;
    private final ProjectionTelemetry telemetry;
    private final ProjectionDbWorker dbWorker;
    private final ExecutorService consumers = Executors.newCachedThreadPool();

    private record ProcessorHandle(AutoCloseable process, java.util.function.BooleanSupplier alive)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.close();
        }
    }

    private final Map<UUID, ProcessorHandle> processors = new HashMap<>();
    private Producer<String, String> producer;
    private Future<?> sinkTask;
    private volatile boolean closed;

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectionRuntime(
            ProjectionProperties config,
            ProjectionCatalogJdbc catalog,
            ProjectionBatchApplier baseline,
            ProjectionSinkJdbc sink,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrap,
            ProjectionTelemetry telemetry,
            ProjectionDbWorker dbWorker) {
        this(config, catalog, baseline, sink, bootstrap, ProjectionCheckpoint.NONE, telemetry, dbWorker);
    }

    public ProjectionRuntime(
            ProjectionProperties config,
            ProjectionCatalogJdbc catalog,
            ProjectionBatchApplier baseline,
            ProjectionSinkJdbc sink,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrap) {
        this(config, catalog, baseline, sink, bootstrap, ProjectionCheckpoint.NONE);
    }

    public ProjectionRuntime(
            ProjectionProperties config,
            ProjectionCatalogJdbc catalog,
            ProjectionBatchApplier baseline,
            ProjectionSinkJdbc sink,
            String bootstrap,
            ProjectionCheckpoint checkpoint) {
        this(config, catalog, baseline, sink, bootstrap, checkpoint, null);
    }

    public ProjectionRuntime(
            ProjectionProperties config,
            ProjectionCatalogJdbc catalog,
            ProjectionBatchApplier baseline,
            ProjectionSinkJdbc sink,
            String bootstrap,
            ProjectionCheckpoint checkpoint,
            ProjectionTelemetry telemetry) {
        this(config, catalog, baseline, sink, bootstrap, checkpoint, telemetry, null);
    }

    public ProjectionRuntime(
            ProjectionProperties config,
            ProjectionCatalogJdbc catalog,
            ProjectionBatchApplier baseline,
            ProjectionSinkJdbc sink,
            String bootstrap,
            ProjectionCheckpoint checkpoint,
            ProjectionTelemetry telemetry,
            ProjectionDbWorker dbWorker) {
        this.config = config;
        this.catalog = catalog;
        this.baseline = baseline;
        this.sink = sink;
        this.bootstrap = bootstrap;
        this.checkpoint = checkpoint;
        this.telemetry = telemetry;
        this.dbWorker = dbWorker;
    }

    @Scheduled(fixedDelayString = "${payment.settlement.projection.fixed-delay-ms:1000}")
    public synchronized void tick() {
        if (closed || !config.isAutoStart()) return;
        try {
            var builds = catalog.running();
            if (builds.isEmpty()) {
                catalog.create(UUID.randomUUID(), config.getMode(), "SYSTEM", "initial projection");
                builds = catalog.running();
            }
            boolean kafkaRequired = builds.stream().anyMatch(build -> !"D".equals(build.mode()));
            if (kafkaRequired) {
                if (producer == null) initialize();
                if (sinkTask == null || sinkTask.isDone()) sinkTask = consumers.submit(this::consumeOutput);
            } else {
                stopKafkaTransport();
            }
            var ids = builds.stream().map(ProjectionCatalogJdbc.Build::id).toList();
            for (UUID old : List.copyOf(processors.keySet())) {
                if (!ids.contains(old)) {
                    processors.remove(old).close();
                }
            }
            for (var build : builds) {
                if (processors.containsKey(build.id())
                        && !processors.get(build.id()).alive().getAsBoolean())
                    processors.remove(build.id()).close();
                if (!"D".equals(build.mode()) && !processors.containsKey(build.id())) start(build);
                if ("BUILDING".equals(build.status())) catalog.materialize(build.id());
                catalog.fanout(build.id());
                if ("D".equals(build.mode())) processDb(build.id());
            }
            long publishDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getMaxPublishRunMillis());
            for (int batch = 0; batch < config.getMaxPublishBatches(); batch++) {
                if (batch > 0 && System.nanoTime() >= publishDeadline) break;
                if (!kafkaRequired || publish() < config.getBatchSize()) break;
            }
            for (var build : builds) {
                if ("BUILDING".equals(build.status())) catalog.freeze(build.id());
                if ("VERIFYING".equals(build.status()) && catalog.verify(build.id())) {
                    if (catalog.generation() == 0) catalog.activate(build.id(), 0);
                }
                if ("READY".equals(build.status()) && catalog.generation() == 0) catalog.activate(build.id(), 0);
            }
        } catch (Exception exception) {
            // Note: 금융 기록은 별도 TX로 계속 쌓인다. payload·개인정보를 로그로 출력하지 않는다.
            log.warn("예상 정산 파이프라인 진행 실패: {}", exception.getClass().getSimpleName());
            failure(ProjectionTelemetry.Phase.TICK);
        } finally {
            if (telemetry != null) telemetry.refresh();
        }
    }

    private void failure(ProjectionTelemetry.Phase phase) {
        if (telemetry != null) telemetry.failure(phase);
    }

    private void processDb(UUID build) {
        if (dbWorker == null) throw new IllegalStateException("DB 집계 실행기가 연결되지 않았다");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getMaxPublishRunMillis());
        for (int batch = 0; batch < config.getMaxPublishBatches(); batch++) {
            if (batch > 0 && System.nanoTime() >= deadline) break;
            if (dbWorker.process(build, config.getBatchSize()) < config.getBatchSize()) break;
        }
    }

    private void stopKafkaTransport() {
        if (sinkTask != null) {
            sinkTask.cancel(true);
            sinkTask = null;
        }
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
            producer = null;
        }
    }

    private void initialize() throws Exception {
        ensureTopic(config.outputTopic());
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, config.getSendTimeout().toMillis());
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        producer = new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer());
        sinkTask = consumers.submit(() -> consumeOutput());
    }

    private void ensureTopic(String name) throws Exception {
        try (var admin = Admin.create(
                Map.of("bootstrap.servers", bootstrap, "default.api.timeout.ms", 5000, "request.timeout.ms", 5000))) {
            try {
                admin.createTopics(List.of(new NewTopic(name, config.getPartitions(), (short) 1)))
                        .all()
                        .get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException exception) {
                if (!(exception.getCause() instanceof TopicExistsException)) throw exception;
            }
        }
    }

    private void start(ProjectionCatalogJdbc.Build build) throws Exception {
        String id = build.id().toString();
        ensureTopic(config.inputTopic(id));
        if ("C".equals(build.mode())) {
            var properties = new Properties();
            properties.put(StreamsConfig.APPLICATION_ID_CONFIG, config.applicationId(id));
            properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            properties.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
            properties.put(StreamsConfig.STATE_DIR_CONFIG, config.getStateDirectory());
            properties.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, config.getThreads());
            properties.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
            properties.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);
            properties.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 8 * 1024 * 1024L);
            var streams = new KafkaStreams(
                    SettlementProjectionTopology.create(id, config.inputTopic(id), config.outputTopic(), checkpoint),
                    properties);
            streams.start();
            processors.put(
                    build.id(),
                    new ProcessorHandle(
                            () -> streams.close(Duration.ofSeconds(5)),
                            () -> streams.state() != KafkaStreams.State.ERROR
                                    && streams.state() != KafkaStreams.State.NOT_RUNNING));
        } else {
            var future = consumers.submit(() -> consumeBaseline(build.id()));
            processors.put(build.id(), new ProcessorHandle(() -> future.cancel(true), () -> !future.isDone()));
        }
    }

    private int publish() throws Exception {
        var deliveries = catalog.claim(config.getBatchSize(), config.getLease().toMillis());
        var sends = new ArrayList<Future<?>>();
        long deadline = System.nanoTime() + config.getSendTimeout().toNanos();
        for (var delivery : deliveries) {
            if (System.nanoTime() >= deadline) break;
            sends.add(producer.send(new ProducerRecord<>(
                    config.inputTopic(delivery.buildId().toString()),
                    Long.toString(delivery.paymentId()),
                    delivery.payload())));
        }
        var acknowledged = new ArrayList<ProjectionCatalogJdbc.Delivery>();
        for (int index = 0; index < sends.size(); index++) {
            var delivery = deliveries.get(index);
            try {
                sends.get(index).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                checkpoint.hit(ProjectionCheckpoint.Stage.PUBLISH_ACKNOWLEDGED, delivery.factKey());
                acknowledged.add(delivery);
            } catch (Exception exception) {
                failure(ProjectionTelemetry.Phase.RELAY);
                catalog.failed(delivery, exception.getClass().getSimpleName());
            }
        }
        for (int index = sends.size(); index < deliveries.size(); index++)
            catalog.failed(deliveries.get(index), "PUBLISH_BUDGET_EXHAUSTED");
        catalog.publishedBatch(acknowledged);
        return sends.size();
    }

    private Properties consumerProperties(String group) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.getBatchSize());
        return properties;
    }

    private void consumeOutput() {
        try (var consumer = new KafkaConsumer<>(
                consumerProperties(config.getTopicPrefix() + ".sink.v1"),
                new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(config.outputTopic()));
            while (!closed && !Thread.currentThread().isInterrupted()) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (records.isEmpty()) continue;
                var results = new ArrayList<ProjectionResult>();
                records.forEach(record -> results.add(ProjectionJson.read(record.value(), ProjectionResult.class)));
                sink.apply(results);
                checkpoint.hit(ProjectionCheckpoint.Stage.SINK_DB_COMMITTED, "sink-batch");
                consumer.commitSync();
            }
        } catch (Exception exception) {
            if (!closed) {
                failure(ProjectionTelemetry.Phase.SINK);
                log.error("예상 정산 sink 중단: {}", exception.getClass().getSimpleName());
            }
        }
    }

    private void consumeBaseline(UUID build) {
        try (var consumer = new KafkaConsumer<>(
                consumerProperties(config.applicationId(build.toString())),
                new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(config.inputTopic(build.toString())));
            while (!closed && !Thread.currentThread().isInterrupted()) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (records.isEmpty()) continue;
                var facts = new ArrayList<SettlementFact>();
                records.forEach(record -> {
                    try {
                        facts.add(ProjectionJson.read(record.value(), SettlementFact.class));
                    } catch (IllegalArgumentException exception) {
                        String identity = SettlementFact.hash(String.valueOf(record.key()) + "|" + record.value());
                        sink.apply(List.of(ProjectionResult.failure(build.toString(), identity, "MALFORMED", 0)));
                    }
                });
                baseline.apply(build, facts);
                checkpoint.hit(ProjectionCheckpoint.Stage.B_DB_COMMITTED, build.toString());
                consumer.commitSync();
            }
        } catch (Exception exception) {
            if (!closed) {
                failure(ProjectionTelemetry.Phase.BASELINE);
                log.error("예상 정산 Consumer 중단: {}", exception.getClass().getSimpleName());
            }
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        for (var processor : processors.values()) {
            try {
                processor.close();
            } catch (Exception exception) {
                log.warn("집계 종료: {}", exception.getClass().getSimpleName());
            }
        }
        consumers.shutdownNow();
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }
}
