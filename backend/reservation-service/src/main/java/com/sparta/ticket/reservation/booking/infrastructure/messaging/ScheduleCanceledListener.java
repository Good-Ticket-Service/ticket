package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.schedulecancel.ScheduleCancellationReceiver;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ScheduleCanceledListener {

    private final ScheduleCancellationReceiver receiver;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = ScheduleCanceledKafkaConfig.TOPIC,
            groupId = ScheduleCanceledKafkaConfig.GROUP_ID,
            containerFactory = ScheduleCanceledKafkaConfig.CONTAINER_FACTORY,
            properties = "auto.offset.reset=earliest")
    void onMessage(String payload) {
        receiver.receive(ScheduleCanceledMessage.parse(payload, objectMapper));
    }
}
