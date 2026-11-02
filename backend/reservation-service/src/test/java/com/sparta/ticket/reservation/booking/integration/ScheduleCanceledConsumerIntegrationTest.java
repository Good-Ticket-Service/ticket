package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestIds;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** 공연 서비스 없이 테스트가 직접 토픽에 메시지를 넣어 소비자를 검증한다. */
class ScheduleCanceledConsumerIntegrationTest extends BookingIntegrationTest {

    private static final String TOPIC = "schedule.canceled";
    private static final String DLT = "schedule.canceled.DLT";
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    private static String message(String eventId, Long scheduleId) {
        return """
                {"eventId":"%s","schemaVersion":1,"occurredAt":"2026-11-01T10:00:00+09:00","scheduleId":%d,\
                "performanceId":7,"canceledAt":"2026-11-01T10:00:00+09:00","reason":"공연 사정"}""".formatted(eventId, scheduleId);
    }

    private void send(Long scheduleId, String payload) throws Exception {
        kafkaTemplate.send(TOPIC, String.valueOf(scheduleId), payload).get();
    }

    private Map<String, Object> recordOf(Long scheduleId) {
        return jdbcTemplate.queryForMap(
                "SELECT event_id, status, canceled_at FROM p_schedule_cancellations WHERE schedule_id = ?", scheduleId);
    }

    private int recordCount(Long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM p_schedule_cancellations WHERE schedule_id = ?", Integer.class, scheduleId);
    }

    private void awaitRecorded(Long scheduleId) {
        await().atMost(WAIT).until(() -> recordCount(scheduleId) == 1);
    }

    @Test
    @DisplayName("회차 취소 메시지를 받으면 취소 기록을 RECEIVED로 남긴다")
    void recordsCancellation() throws Exception {
        Long scheduleId = TestIds.next();
        String eventId = UUID.randomUUID().toString();

        send(scheduleId, message(eventId, scheduleId));

        awaitRecorded(scheduleId);
        assertThat(recordOf(scheduleId)).containsEntry("event_id", eventId).containsEntry("status", "RECEIVED");
    }

    @Test
    @DisplayName("같은 회차의 메시지가 다시 와도 기록은 처음 한 건이다. eventId가 달라도 같다")
    void duplicateKeepsFirstRecord() throws Exception {
        Long scheduleId = TestIds.next();
        Long marker = TestIds.next();
        String firstEvent = UUID.randomUUID().toString();

        send(scheduleId, message(firstEvent, scheduleId));
        send(scheduleId, message(firstEvent, scheduleId));
        send(scheduleId, message(UUID.randomUUID().toString(), scheduleId));
        send(marker, message(UUID.randomUUID().toString(), marker));

        awaitRecorded(marker);
        assertThat(recordCount(scheduleId)).isEqualTo(1);
        assertThat(recordOf(scheduleId)).containsEntry("event_id", firstEvent);
    }

    @Test
    @DisplayName("해석할 수 없는 메시지는 DLT로 보내고 뒤의 메시지를 막지 않는다")
    void poisonGoesToDltAndDoesNotBlock() throws Exception {
        Long scheduleId = TestIds.next();
        Long unknownVersion = TestIds.next();
        Long missingField = TestIds.next();
        Long healthy = TestIds.next();
        String broken = "{not-json-" + UUID.randomUUID();
        String futureVersion = message(UUID.randomUUID().toString(), unknownVersion)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        String noCanceledAt =
                "{\"eventId\":\"%s\",\"schemaVersion\":1,\"scheduleId\":%d}".formatted(UUID.randomUUID(), missingField);
        Long tooLongEventId = TestIds.next();
        String longEventId = message(UUID.randomUUID() + "-longer-than-a-uuid", tooLongEventId);

        send(scheduleId, broken);
        send(unknownVersion, futureVersion);
        send(missingField, noCanceledAt);
        send(tooLongEventId, longEventId);
        send(healthy, message(UUID.randomUUID().toString(), healthy));

        awaitRecorded(healthy);
        assertThat(recordCount(unknownVersion)).isZero();
        assertThat(recordCount(missingField)).isZero();
        assertThat(recordCount(tooLongEventId)).isZero();
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(deadLetters()).contains(broken, futureVersion, noCanceledAt, longEventId));
    }

    @Test
    @DisplayName("기록 저장이 실패하면 메시지를 잃지 않고 DB가 돌아온 뒤 기록한다")
    void retriesUntilDatabaseRecovers() throws Exception {
        Long scheduleId = TestIds.next();
        jdbcTemplate.execute("ALTER TABLE p_schedule_cancellations RENAME TO p_schedule_cancellations_down");
        try {
            send(scheduleId, message(UUID.randomUUID().toString(), scheduleId));
            Thread.sleep(3_000);
        } finally {
            jdbcTemplate.execute("ALTER TABLE p_schedule_cancellations_down RENAME TO p_schedule_cancellations");
        }

        awaitRecorded(scheduleId);
        assertThat(deadLetters()).noneMatch(payload -> payload.contains(":" + scheduleId + ","));
    }

    private List<String> deadLetters() {
        try (Consumer<String, String> consumer =
                consumerFactory.createConsumer("dlt-check-" + UUID.randomUUID(), "dlt-check")) {
            List<TopicPartition> partitions = consumer.partitionsFor(DLT).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            return java.util.stream.StreamSupport.stream(records.spliterator(), false)
                    .map(record -> record.value())
                    .toList();
        }
    }
}
