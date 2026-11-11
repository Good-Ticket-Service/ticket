package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** String 역직렬화 뒤 엄격히 검사한다. 파싱 예외의 원문·인증값을 밖으로 전달하지 않는다. */
final class PaymentConfirmMessage {
    private PaymentConfirmMessage() {}

    static MessageConfirmCommand parse(ObjectMapper mapper, String key, String value, int maxBytes) {
        try {
            if (value == null || value.length() > maxBytes || value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
                throw new IllegalArgumentException();
            JsonNode node = mapper.reader()
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(value);
            if (node == null
                    || !node.isObject()
                    || !node.path("schemaVersion").isIntegralNumber()
                    || !node.path("schemaVersion").canConvertToInt()
                    || node.path("schemaVersion").intValue() != 1) throw new IllegalArgumentException();
            UUID.fromString(text(node, "eventId", 36));
            String order = text(node, "orderId", 64);
            String token = text(node, "pgAuthToken", 255);
            if (!node.path("reservationId").isIntegralNumber()
                    || !node.path("reservationId").canConvertToLong()
                    || node.path("reservationId").longValue() <= 0
                    || !node.path("amount").isIntegralNumber()
                    || !node.path("amount").canConvertToInt()
                    || node.path("amount").intValue() < 100) throw new IllegalArgumentException();
            long reservation = node.path("reservationId").longValue();
            if (!Long.toString(reservation).equals(key)) throw new IllegalArgumentException();
            var occurred = time(node, "occurredAt");
            var before = time(node, "confirmBefore");
            if (before.isAfter(occurred.plusMinutes(5))) throw new IllegalArgumentException();
            return new MessageConfirmCommand(
                    order, reservation, node.path("amount").intValue(), before.toLocalDateTime(), token);
        } catch (Exception exception) {
            throw new InvalidMessage();
        }
    }

    private static String text(JsonNode node, String field, int limit) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException();
        String value = node.path(field).textValue();
        if (value.isBlank() || value.length() > limit) throw new IllegalArgumentException();
        return value;
    }

    private static OffsetDateTime time(JsonNode node, String field) {
        var value = OffsetDateTime.parse(text(node, field, 50));
        if (!value.getOffset().equals(ZoneOffset.ofHours(9))) throw new IllegalArgumentException();
        return value;
    }

    static final class InvalidMessage extends RuntimeException {
        InvalidMessage() {
            super("INVALID_CONFIRM_MESSAGE", null, false, false);
        }
    }
}
