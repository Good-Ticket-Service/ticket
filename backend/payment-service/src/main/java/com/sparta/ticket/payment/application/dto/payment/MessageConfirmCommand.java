package com.sparta.ticket.payment.application.dto.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/** 인증값은 원본 Kafka에만 남긴다. DB에는 요청 의미의 고정 hash만 보존한다. */
public record MessageConfirmCommand(
        String orderId, long reservationId, int amount, LocalDateTime confirmBefore, String pgAuthToken) {
    public MessageConfirmCommand {
        confirmBefore = confirmBefore.truncatedTo(ChronoUnit.MICROS);
    }

    public String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : java.util.List.of(
                    orderId,
                    Long.toString(reservationId),
                    Integer.toString(amount),
                    confirmBefore.truncatedTo(ChronoUnit.MICROS).toString(),
                    pgAuthToken)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(
                        java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    @Override
    public String toString() {
        return "MessageConfirmCommand[REDACTED]";
    }
}
