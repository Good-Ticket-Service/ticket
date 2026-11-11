package com.sparta.ticket.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentConfirmMessageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    String valid() throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId",
                UUID.randomUUID().toString(),
                "schemaVersion",
                1,
                "occurredAt",
                "2026-11-11T12:00:00+09:00",
                "orderId",
                "order",
                "reservationId",
                12,
                "amount",
                10000,
                "confirmBefore",
                "2026-11-11T12:05:00+09:00",
                "pgAuthToken",
                "sensitive-test-token"));
    }

    @Test
    void validatesTypesLimitsTimeAndKeyWithoutLeakingPayload() throws Exception {
        String valid = valid();
        var command = PaymentConfirmMessage.parse(mapper, "12", valid, 8192);
        assertThat(command.pgAuthToken()).isEqualTo("sensitive-test-token");
        assertThat(command.toString()).doesNotContain("sensitive-test-token");
        assertThat(command.fingerprint()).hasSize(64);
        assertThat(PaymentConfirmMessage.parse(mapper, "12", valid.replace(command.pgAuthToken(), "changed"), 8192)
                        .fingerprint())
                .isNotEqualTo(command.fingerprint());
        String[] invalid = {
            "null",
            "",
            "{}",
            "[]",
            "{broken-sensitive-test-token",
            valid + "{}",
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"amount\":10000", "\"amount\":2147483648"),
            valid.replace("\"amount\":10000", "\"amount\":10000.0"),
            valid.replace("+09:00", "Z"),
            valid.replace("12:05:00", "12:06:00"),
            valid.substring(0, valid.length() - 1) + ",\"schemaVersion\":1}"
        };
        for (String value : invalid) {
            assertThatThrownBy(() -> PaymentConfirmMessage.parse(mapper, "12", value, 8192))
                    .isInstanceOf(PaymentConfirmMessage.InvalidMessage.class)
                    .hasMessage("INVALID_CONFIRM_MESSAGE")
                    .hasNoCause();
        }
        assertThatThrownBy(() -> PaymentConfirmMessage.parse(mapper, "other", valid, 8192))
                .isInstanceOf(PaymentConfirmMessage.InvalidMessage.class);
        assertThatThrownBy(() -> PaymentConfirmMessage.parse(mapper, "12", null, 8192))
                .isInstanceOf(PaymentConfirmMessage.InvalidMessage.class);
    }
}
