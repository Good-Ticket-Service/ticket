package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.persistence.payment.PaymentResultOutboxJdbc;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 실제 결과 발행과 예매 JVM의 Consumer·좌석·쿠폰을 잇는다. 초기 행과 PG 응답만 fixture다. */
@EnabledIfSystemProperty(named = "payment.result.integration", matches = "true")
class PaymentResultReservationIntegrationTest extends PaymentIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String GROUP = "reservation-booking-payment-result";

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentResultOutboxJdbc outbox;

    @Autowired
    private JdbcTemplate paymentJdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private com.sparta.ticket.payment.application.service.payment.MessageApprovalTransactions messageTransactions;

    @Autowired
    private com.sparta.ticket.payment.application.service.payment.PaymentTransactions paymentTransactions;

    @Test
    @EnabledIfSystemProperty(named = "payment.confirm.integration", matches = "true")
    void approvalOutboxThroughRealPgAndResultConsumerPreservesTicketCouponAndMoney() throws Exception {
        Path output = Path.of("build/payment-confirm-integration").toAbsolutePath();
        Files.createDirectories(output);
        try (var database = new PostgreSQLContainer<>("postgres:16");
                var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
                var broker = new KafkaContainer("apache/kafka:4.3.1")) {
            database.start();
            redis.start();
            broker.start();
            var reservation = new JdbcTemplate(
                    new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
            var schema = new ResourceDatabasePopulator();
            for (String sql : new String[] {"schema.sql", "booking.sql", "seat.sql", "coupon.sql"})
                schema.addScript(new FileSystemResource("../sql/reservation-service/" + sql));
            schema.execute(reservation.getDataSource());
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                admin.createTopics(java.util.List.of(
                                new org.apache.kafka.clients.admin.NewTopic(
                                        PaymentConfirmConsumer.TOPIC, 12, (short) 1),
                                new org.apache.kafka.clients.admin.NewTopic(
                                        PaymentConfirmConsumer.TOPIC + ".DLT", 12, (short) 1),
                                new PaymentResultTopics().paymentResultTopic(),
                                new PaymentResultTopics().paymentResultDeadLetterTopic()))
                        .all()
                        .get(10, TimeUnit.SECONDS);
            }
            int port;
            try (var socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Process application =
                    startReservation(database, redis, broker, output.resolve("reservation.log"), port, true);
            var producer = new DefaultKafkaProducerFactory<String, String>(
                    Map.of(
                            "bootstrap.servers",
                            broker.getBootstrapServers(),
                            "acks",
                            "all",
                            "max.block.ms",
                            1000,
                            "request.timeout.ms",
                            2000,
                            "delivery.timeout.ms",
                            5000),
                    new StringSerializer(),
                    new StringSerializer());
            var fakeAdmin = new com.sparta.ticket.payment.support.pg.FakePgAdmin(
                    com.sparta.ticket.payment.support.pg.FakePgContainer.baseUrl());
            fakeAdmin.clearFaults();
            String pgUrl = com.sparta.ticket.payment.support.pg.FakePgContainer.baseUrl();
            var pgProperties = new com.sparta.ticket.payment.infrastructure.pg.PgProperties(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(10),
                    new com.sparta.ticket.payment.infrastructure.pg.PgProperties.Toss(pgUrl, "test_sk_fake_toss"),
                    new com.sparta.ticket.payment.infrastructure.pg.PgProperties.Kakao(
                            pgUrl,
                            "DEV_fake_kakao",
                            "TC0ONETIME",
                            "http://localhost/approve",
                            "http://localhost/cancel",
                            "http://localhost/fail"));
            var actualClient = new com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient(
                    org.springframework.web.client.RestClient.builder(),
                    pgProperties,
                    mapper,
                    null,
                    new com.sparta.ticket.payment.infrastructure.pg.PgCallGuard(null, null));
            var clients = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                    Map.of("toss", actualClient));
            var actualService = new com.sparta.ticket.payment.application.service.payment.MessageApprovalService(
                    messageTransactions,
                    paymentTransactions,
                    new com.sparta.ticket.payment.application.service.pg.PgClientRegistry(
                            clients.getBeanProvider(com.sparta.ticket.payment.application.port.pg.PgClient.class)),
                    paymentClock,
                    new com.sparta.ticket.payment.application.service.payment.MessageApprovalProperties());
            var kafka = new KafkaTemplate<>(producer);
            try (var consumer = PaymentConfirmConsumerIntegrationTest.start(
                    broker.getBootstrapServers(),
                    kafka,
                    actualService,
                    mapper,
                    new SimpleMeterRegistry(),
                    paymentClock)) {
                var http = org.springframework.web.client.RestClient.builder()
                        .baseUrl("http://localhost:" + port)
                        .build();
                await().atMost(WAIT)
                        .ignoreExceptions()
                        .until(() -> http.get()
                                .uri("/actuator/health")
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .is2xxSuccessful());
                var relay = new PaymentResultRelay(
                        outbox,
                        kafka,
                        new PaymentResultRelayProperties(
                                10,
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(1),
                                Duration.ofMillis(10)),
                        new SimpleMeterRegistry());
                var evidence = new LinkedHashMap<String, Object>();
                for (int index = 0; index < 2; index++) {
                    long id = 820001 + index;
                    String order = "confirm-integration-" + id;
                    seed(reservation, id, order, false);
                    reservation.update("UPDATE p_reservations SET status='HOLD' WHERE reservation_id=?", id);
                    var expires = reservation.queryForObject(
                            "SELECT hold_expires_at FROM p_reservations WHERE reservation_id=?",
                            LocalDateTime.class,
                            id);
                    service.checkout(
                            new CheckoutCommand(order, id, id, 2L, 10L, id, PgProvider.TOSS, 11000, 1000, expires));
                    String token = fakeAdmin.tossCheckout(order, 10000);
                    if (index == 1) fakeAdmin.fault("TOSS", "CONFIRM", "{\"failRate\":1}");
                    long started = System.nanoTime();
                    String response = http.post()
                            .uri("/api/reservations/{id}/payments/confirm", id)
                            .header("X-User-Id", Long.toString(id))
                            .header("X-Username", "TEST")
                            .header("X-User-Role", "USER")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body(Map.of("orderId", order, "pgAuthToken", token))
                            .retrieve()
                            .body(String.class);
                    assertThat(response).contains("PAYMENT_IN_PROGRESS");
                    await().atMost(WAIT)
                            .until(() -> paymentJdbc.queryForObject(
                                            "SELECT count(*) FROM p_payment_result_outbox WHERE order_id=?",
                                            Long.class,
                                            order)
                                    == 1);
                    await().atMost(WAIT).untilAsserted(() -> {
                        relay.publishOnce();
                        assertThat(outbox.pending()).isZero();
                    });
                    if (index == 0) awaitState(reservation, id, "CONFIRMED", "SOLD", "USED");
                    else awaitState(reservation, id, "HOLD", "HOLD", "RESERVED");
                    assertThat(receipts(reservation, order)).isEqualTo(1);
                    assertThat(paymentJdbc.queryForObject(
                                    "SELECT message_confirm_before IS NOT NULL FROM p_payments WHERE order_id=?",
                                    Boolean.class,
                                    order))
                            .isTrue();
                    assertThat(com.sparta.ticket.payment.support.pg.FakePgContainer.jdbc()
                                    .queryForObject(
                                            "SELECT count(*) FROM pg_transaction WHERE order_id=?", Long.class, order))
                            .isEqualTo(1);
                    var item = new LinkedHashMap<String, Object>();
                    item.put("finalState", snapshot(reservation, id));
                    item.put("pgState", fakeAdmin.transactionStatus(order));
                    item.put("observedCompletionMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    item.put("resultReceiptCount", receipts(reservation, order));
                    evidence.put(index == 0 ? "approved" : "rejected", item);
                }
                settlementJobs.drain();
                assertThat(paymentJdbc.queryForObject(
                                "SELECT sum(debit)-sum(credit) FROM p_ledger_entries", Long.class))
                        .isZero();
                assertThat(paymentJdbc.queryForObject(
                                "SELECT count(*) FROM p_ledger_transactions WHERE source_type='SALE'", Long.class))
                        .isEqualTo(1);
                evidence.put("ledgerDifference", 0);
                evidence.put(
                        "source",
                        "reservation HTTP + actual Outbox, actual payment Consumer and Toss HTTP adapter, real FakePG");
                Files.writeString(
                        output.resolve("results.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            } finally {
                fakeAdmin.clearFaults();
                application.destroy();
                if (!application.waitFor(10, TimeUnit.SECONDS)) {
                    application.destroyForcibly();
                    application.waitFor(10, TimeUnit.SECONDS);
                }
                producer.destroy();
            }
        }
    }

    @Test
    void deliversRealResultsDeduplicatesAndRecoversAfterBrokerRestart() throws Exception {
        Path output = Path.of("build/payment-result-integration").toAbsolutePath();
        Files.createDirectories(output);
        try (var database = new PostgreSQLContainer<>("postgres:16");
                var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
                var broker = new KafkaContainer("apache/kafka:4.3.1")) {
            database.start();
            redis.start();
            // Note: Docker가 stop/start 때 임의 호스트 포트를 재할당하면 장애 복구 대신 주소 변경 시험이 된다.
            try (var socket = new ServerSocket(0)) {
                broker.setPortBindings(java.util.List.of(socket.getLocalPort() + ":9092"));
            }
            broker.start();
            // Note: 시험에서는 자동 relay를 끄므로 운영 토픽 설정을 직접 적용한다. 브로커 기본 1개 파티션에 기대지 않는다.
            try (var topics = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                var definitions = new PaymentResultTopics();
                topics.createTopics(java.util.List.of(
                                definitions.paymentResultTopic(), definitions.paymentResultDeadLetterTopic()))
                        .all()
                        .get(10, TimeUnit.SECONDS);
            }
            var reservation = new JdbcTemplate(
                    new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
            var schema = new ResourceDatabasePopulator();
            for (String sql : new String[] {"schema.sql", "booking.sql", "seat.sql", "coupon.sql"}) {
                schema.addScript(new FileSystemResource("../sql/reservation-service/" + sql));
            }
            schema.execute(reservation.getDataSource());
            var producer = new DefaultKafkaProducerFactory<String, String>(
                    Map.of(
                            "bootstrap.servers",
                            broker.getBootstrapServers(),
                            "acks",
                            "all",
                            "enable.idempotence",
                            true,
                            "max.block.ms",
                            1000,
                            "request.timeout.ms",
                            2000,
                            "delivery.timeout.ms",
                            5000),
                    new StringSerializer(),
                    new StringSerializer());
            Process application = startReservation(database, redis, broker, output.resolve("reservation.log"));
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                await().atMost(WAIT).untilAsserted(() -> {
                    assertThat(application.isAlive())
                            .as("예매 JVM: reservation.log 확인")
                            .isTrue();
                    var members = admin.describeConsumerGroups(java.util.List.of(GROUP))
                            .all()
                            .get(5, TimeUnit.SECONDS)
                            .get(GROUP)
                            .members();
                    assertThat(members.stream()
                                    .mapToInt(member -> member.assignment()
                                            .topicPartitions()
                                            .size())
                                    .sum())
                            .isEqualTo(12);
                });
                var kafka = new KafkaTemplate<>(producer);
                var relay = new PaymentResultRelay(
                        outbox,
                        kafka,
                        new PaymentResultRelayProperties(
                                10,
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(1),
                                Duration.ofMillis(10)),
                        new SimpleMeterRegistry());
                var evidence = new LinkedHashMap<String, Object>();

                String success = prepare(reservation, 810001, false);
                assertThat(relay.publishOnce()).isEqualTo(1);
                awaitState(reservation, 810001, "CONFIRMED", "SOLD", "USED");
                var first = snapshot(reservation, 810001);
                assertThat(first.get("ticket_code")).isNotNull();
                assertThat(first.get("used_at")).isNotNull();
                String payload = payload(success);
                var replay = kafka.send("payment.result", "810001", payload).get(10, TimeUnit.SECONDS);
                awaitCommitted(
                        admin,
                        replay.getRecordMetadata().partition(),
                        replay.getRecordMetadata().offset() + 1);
                assertThat(snapshot(reservation, 810001)).isEqualTo(first);
                assertThat(receipts(reservation, success)).isEqualTo(1);
                evidence.put("successAndDuplicate", first);
                evidence.put("successReceiptCount", receipts(reservation, success));

                String failure = prepare(reservation, 810002, true);
                assertThat(relay.publishOnce()).isEqualTo(1);
                awaitState(reservation, 810002, "EXPIRED", "AVAILABLE", "AVAILABLE");
                assertThat(receipts(reservation, failure)).isEqualTo(1);
                evidence.put("failure", snapshot(reservation, 810002));
                evidence.put("failureReceiptCount", receipts(reservation, failure));

                String delayed = prepare(reservation, 810003, false);
                String frozen = payload(delayed);
                broker.getDockerClient()
                        .stopContainerCmd(broker.getContainerId())
                        .withTimeout(5)
                        .exec();
                assertThat(relay.publishOnce()).isZero();
                assertThat(outbox.pending()).isEqualTo(1);
                assertThat(payload(delayed)).isEqualTo(frozen);
                assertThat(snapshot(reservation, 810003).get("reservation_status"))
                        .isEqualTo("PAYMENT_IN_PROGRESS");
                evidence.put("brokerDownPending", outbox.pending());
                broker.getDockerClient()
                        .startContainerCmd(broker.getContainerId())
                        .exec();
                await().atMost(WAIT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                    relay.publishOnce();
                    assertThat(outbox.pending()).isZero();
                });
                awaitState(reservation, 810003, "CONFIRMED", "SOLD", "USED");
                assertThat(receipts(reservation, delayed)).isEqualTo(1);
                assertThat(payload(delayed)).isEqualTo(frozen);
                evidence.put("brokerRecovered", snapshot(reservation, 810003));
                evidence.put("recoveredReceiptCount", receipts(reservation, delayed));
                evidence.put("finalPending", outbox.pending());
                evidence.put("pgApprovalCalls", pg.approveCalls());
                assertThat(pg.approveCalls()).isEqualTo(3);
                Files.writeString(
                        output.resolve("results.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            } finally {
                application.destroy();
                if (!application.waitFor(10, TimeUnit.SECONDS)) {
                    application.destroyForcibly();
                    application.waitFor(10, TimeUnit.SECONDS);
                }
                producer.destroy();
            }
        }
    }

    private Process startReservation(
            PostgreSQLContainer<?> database, GenericContainer<?> redis, KafkaContainer broker, Path log)
            throws Exception {
        return startReservation(database, redis, broker, log, 0, false);
    }

    private Process startReservation(
            PostgreSQLContainer<?> database,
            GenericContainer<?> redis,
            KafkaContainer broker,
            Path log,
            int port,
            boolean messageMode)
            throws Exception {
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-Xmx384m");
        args.add("-Dfile.encoding=UTF-8");
        args.add("-jar");
        args.add(System.getProperty("reservation.jar"));
        args.add(
                "--spring.config.additional-location=optional:file:../config-repo/application.yml,optional:file:../config-repo/reservation-service.yml");
        args.add("--spring.profiles.active=integration");
        args.add("--spring.cloud.config.enabled=false");
        args.add("--eureka.client.enabled=false");
        args.add("--server.port=" + port);
        args.add("--spring.datasource.url=" + database.getJdbcUrl());
        args.add("--spring.datasource.username=" + database.getUsername());
        args.add("--spring.datasource.password=" + database.getPassword());
        args.add("--spring.jpa.properties.hibernate.default_schema=public");
        args.add("--spring.kafka.bootstrap-servers=" + broker.getBootstrapServers());
        args.add("--spring.kafka.consumer.auto-offset-reset=earliest");
        args.add("--spring.data.redis.host=" + redis.getHost());
        args.add("--spring.data.redis.port=" + redis.getMappedPort(6379));
        args.add("--reservation.booking.scheduler.enabled=" + messageMode);
        args.add("--reservation.booking.payment-confirm.via-message=" + messageMode);
        args.add("--reservation.coupon.scheduler.enabled=false");
        return new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    private String prepare(JdbcTemplate reservation, long id, boolean rejected) {
        String order = "result-integration-" + id;
        seed(reservation, id, order, rejected);
        service.checkout(new CheckoutCommand(
                order,
                id,
                id,
                2L,
                10L,
                id,
                PgProvider.TOSS,
                11000,
                1000,
                LocalDateTime.now(paymentClock).plusMinutes(10)));
        pg.approveReturns(request -> rejected
                ? new PgResult.Rejected("CARD_DECLINED", "fixture rejection")
                : new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(paymentClock)));
        service.confirm(order, new ConfirmCommand("fixture-token-" + id, 10000));
        assertThat(outbox.pending()).isEqualTo(1);
        return order;
    }

    private void seed(JdbcTemplate jdbc, long id, String order, boolean expired) {
        jdbc.update("""
                INSERT INTO p_reservations(reservation_id,user_id,schedule_id,performance_id,seller_id,
                  schedule_start_at,performance_title,status,hold_expires_at,order_id,payment_amount,
                  user_coupon_id,discount_amount,created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,10,2,LOCALTIMESTAMP+interval '1 day','integration','PAYMENT_IN_PROGRESS',
                  LOCALTIMESTAMP+(?*interval '1 minute'),?,10000,?,1000,LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST')
                """, id, id, id, expired ? -10 : 10, order, id);
        jdbc.update("""
                INSERT INTO p_tickets(reservation_id,schedule_seat_id,price,created_at,created_by,updated_at,updated_by)
                VALUES(?,?,11000,LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST')
                """, id, id);
        jdbc.update("""
                INSERT INTO p_schedule_seats(id,schedule_id,venue_seat_id,venue_seat_grade_id,grade_name,
                  base_price,section,row_label,seat_number,is_sellable,status,reservation_id,hold_expires_at,
                  config_version,created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,1,'A',11000,'A','A',1,true,'HOLD',?,
                  (SELECT hold_expires_at FROM p_reservations WHERE reservation_id=?),
                  1,LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST')
                """, id, id, id, id, id);
        jdbc.update("""
                INSERT INTO p_coupons(coupon_id,name,discount_amount,total_quantity,issued_quantity,
                  issue_start_at,issue_end_at,valid_days,created_at,created_by,updated_at,updated_by)
                VALUES(?,'integration',1000,1,1,LOCALTIMESTAMP-interval '1 day',LOCALTIMESTAMP+interval '1 day',
                  3,LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST')
                """, id);
        jdbc.update("""
                INSERT INTO p_user_coupons(user_coupon_id,user_id,coupon_id,discount_amount,status,issued_at,
                  expires_at,reservation_id,created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,1000,'RESERVED',LOCALTIMESTAMP,LOCALTIMESTAMP+interval '1 day',?,
                  LOCALTIMESTAMP,'TEST',LOCALTIMESTAMP,'TEST')
                """, id, id, id, id);
    }

    private Map<String, Object> snapshot(JdbcTemplate jdbc, long id) {
        return jdbc.queryForMap("""
                SELECT r.status AS reservation_status,s.status AS seat_status,c.status AS coupon_status,
                  t.ticket_code,c.used_at,r.updated_at
                FROM p_reservations r JOIN p_tickets t ON t.reservation_id=r.reservation_id
                  JOIN p_schedule_seats s ON s.id=t.schedule_seat_id
                  JOIN p_user_coupons c ON c.user_coupon_id=r.user_coupon_id WHERE r.reservation_id=?
                """, id);
    }

    private void awaitState(JdbcTemplate jdbc, long id, String reservation, String seat, String coupon) {
        await().atMost(WAIT).untilAsserted(() -> {
            var row = snapshot(jdbc, id);
            assertThat(row.get("reservation_status")).isEqualTo(reservation);
            assertThat(row.get("seat_status")).isEqualTo(seat);
            assertThat(row.get("coupon_status")).isEqualTo(coupon);
        });
    }

    private long receipts(JdbcTemplate jdbc, String order) {
        return jdbc.queryForObject("SELECT count(*) FROM p_payment_result_events WHERE order_id=?", Long.class, order);
    }

    private String payload(String order) {
        return paymentJdbc.queryForObject(
                "SELECT payload FROM p_payment_result_outbox WHERE order_id=?", String.class, order);
    }

    private void awaitCommitted(Admin admin, int partition, long offset) {
        await().atMost(WAIT).untilAsserted(() -> {
            var committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS)
                    .get(new TopicPartition("payment.result", partition));
            assertThat(committed).isNotNull();
            assertThat(committed.offset()).isGreaterThanOrEqualTo(offset);
        });
    }
}
