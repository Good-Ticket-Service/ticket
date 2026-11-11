package com.sparta.ticket.payment.domain.model.settlement.projection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/** 원장에 반영된 판매·완료 환불의 고정 기여. 전송 식별자와 업무 식별자는 다르다. */
public record SettlementFact(
        String eventId,
        String sourceFactKey,
        Kind kind,
        String action,
        long paymentId,
        Long refundId,
        long sellerId,
        long scheduleId,
        LocalDateTime occurredAt,
        long sellerAmount,
        String policyVersion) {
    public enum Kind {
        SALE,
        REFUND
    }

    public SettlementFact {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(occurredAt);
        if (eventId == null
                || eventId.isBlank()
                || policyVersion == null
                || policyVersion.isBlank()
                || paymentId <= 0
                || sellerId <= 0
                || scheduleId <= 0
                || sellerAmount < 0) throw new IllegalArgumentException("집계 원천의 식별·금액·정책이 올바르지 않다");
        boolean sale = kind == Kind.SALE && "POST".equals(action) && refundId == null;
        boolean refund = kind == Kind.REFUND
                && refundId != null
                && refundId > 0
                && ("REVERSE".equals(action) || "RECEIVABLE".equals(action));
        if ((!sale && !refund)
                || !key(kind, sale ? paymentId : refundId, action).equals(sourceFactKey))
            throw new IllegalArgumentException("집계 원천의 업무 키가 올바르지 않다");
    }

    public static String key(Kind kind, long sourceId, String action) {
        return "v1:" + kind + ":" + sourceId + ":" + action;
    }

    public LocalDate businessDate() {
        return occurredAt.toLocalDate();
    }

    public String aggregateKey() {
        return sellerId + ":" + scheduleId + ":" + businessDate();
    }

    /** 전송 때 달라질 수 있는 eventId를 제외하고 금액·귀속·원 판매 연결을 비교한다. */
    public String meaningHash() {
        return hash(sourceFactKey + "|" + kind + "|" + action + "|" + paymentId + "|" + refundId + "|" + sellerId + "|"
                + scheduleId + "|" + occurredAt + "|" + sellerAmount + "|" + policyVersion);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
