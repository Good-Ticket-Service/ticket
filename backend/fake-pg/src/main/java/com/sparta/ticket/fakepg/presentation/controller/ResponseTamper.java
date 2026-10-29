package com.sparta.ticket.fakepg.presentation.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparta.ticket.fakepg.domain.model.fault.TamperMode;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 성공 응답 본문을 망가뜨린다. 거래 기록과 멱등 키에 저장한 응답은 그대로 두고, 이번에 내보내는 본문만 바꾼다.
 * 토스와 카카오는 필드 이름이 달라 제공자별로 바꿀 필드를 정한다.
 */
@Component
@RequiredArgsConstructor
public class ResponseTamper {

    private static final int AMOUNT_DELTA = 1_000;

    private final ObjectMapper objectMapper;

    public JsonNode apply(PgProvider provider, TamperMode mode, Object body) {
        JsonNode tree = body instanceof String json ? read(json) : objectMapper.valueToTree(body);
        if (!(tree instanceof ObjectNode node)) {
            return tree;
        }
        Fields fields = provider == PgProvider.TOSS ? Fields.TOSS : Fields.KAKAO;
        switch (mode) {
            case MISSING_FIELDS -> fields.removable().forEach(node::remove);
            case OTHER_TRANSACTION -> {
                node.put(
                        fields.key(),
                        fields.keyPrefix() + "other"
                                + UUID.randomUUID().toString().replace("-", ""));
                node.put(
                        fields.orderId(), "other-" + node.path(fields.orderId()).asText());
            }
            case WRONG_AMOUNT -> fields.amounts().forEach(path -> addAmount(node, path));
        }
        return node;
    }

    /** 0인 금액(취소 전 취소액 등)은 그대로 둔다. 있는 금액만 틀리게 만든다. */
    private static void addAmount(ObjectNode node, String path) {
        String[] parts = path.split("\\.");
        JsonNode parent = node;
        for (int i = 0; i < parts.length - 1; i++) {
            parent = parts[i].matches("\\d+") ? parent.path(Integer.parseInt(parts[i])) : parent.path(parts[i]);
        }
        String last = parts[parts.length - 1];
        if (parent instanceof ObjectNode target && target.path(last).asInt(0) > 0) {
            target.put(last, target.path(last).asInt() + AMOUNT_DELTA);
        }
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장한 응답을 읽지 못했다", e);
        }
    }

    private record Fields(String key, String keyPrefix, String orderId, List<String> removable, List<String> amounts) {

        static final Fields TOSS = new Fields(
                "paymentKey",
                "tgen_",
                "orderId",
                List.of("paymentKey", "totalAmount", "balanceAmount", "approvedAt", "cancels"),
                List.of("totalAmount", "balanceAmount", "cancels.0.cancelAmount"));

        static final Fields KAKAO = new Fields(
                "tid",
                "T",
                "partner_order_id",
                List.of("tid", "amount", "canceled_amount", "cancel_available_amount", "approved_at", "canceled_at"),
                List.of("amount.total", "canceled_amount.total", "cancel_available_amount.total"));
    }
}
