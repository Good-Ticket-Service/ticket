package com.sparta.ticket.payment.infrastructure.pg.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.infrastructure.pg.PgHttp;
import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import com.sparta.ticket.payment.infrastructure.pg.PgJson;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 모의 PG 지급대행 어댑터(fake-pg {@code /v1/payouts}). 토스 결제와 같은 키로 Basic 인증한다. 전송 분류는 PgHttp를 쓰지만
 * 결제의 승인/거절 의미(PgResult)는 쓰지 않는다. 연결 실패도 Unknown으로 돌려 같은 키 조회로 확정하게 한다.
 */
@Component
public class PgPayoutAgency implements PayoutAgency {

    private static final String PATH = "/v1/payouts";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String authorization;

    public PgPayoutAgency(RestClient.Builder builder, PgProperties properties, ObjectMapper objectMapper) {
        this.restClient = PgHttp.restClient(builder, properties.toss().baseUrl(), properties);
        this.objectMapper = objectMapper;
        this.authorization = "Basic "
                + Base64.getEncoder()
                        .encodeToString((properties.toss().secretKey() + ":").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PayoutAgencyResult request(PayoutOrder order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerRef", order.sellerRef());
        body.put("accountRef", order.accountRef());
        body.put("holderMasked", order.holderMasked());
        body.put("amount", order.amount());
        PgCall call = PgHttp.call(
                client -> client.post()
                        .uri(PATH)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header("Idempotency-Key", order.idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                restClient);
        return interpret(call, false);
    }

    @Override
    public PayoutAgencyResult inquire(String idempotencyKey) {
        PgCall call = PgHttp.call(
                client -> client.get()
                        .uri(PATH + "/{key}", idempotencyKey)
                        .header(HttpHeaders.AUTHORIZATION, authorization),
                restClient);
        return interpret(call, true);
    }

    private PayoutAgencyResult interpret(PgCall call, boolean inquiry) {
        if (call instanceof PgCall.NotSent notSent) return new PayoutAgencyResult.Unknown(notSent.reason());
        if (call instanceof PgCall.SentButFailed failed) return new PayoutAgencyResult.Unknown(failed.reason());
        PgCall.Responded response = (PgCall.Responded) call;
        if (response.isSuccessful()) {
            return PgJson.parse(objectMapper, response.body())
                    .<PayoutAgencyResult>map(this::observed)
                    .orElseGet(() -> new PayoutAgencyResult.Malformed("지급대행 응답 본문 해석 실패"));
        }
        String code = PgJson.parse(objectMapper, response.body())
                .map(node -> node.path("code").asText(""))
                .orElse("");
        int status = response.status();
        if (inquiry && status == 404 && "NOT_FOUND_PAYOUT".equals(code)) return new PayoutAgencyResult.NotFound();
        if (!inquiry && status == 400 && "INVALID_REQUEST".equals(code))
            return new PayoutAgencyResult.RequestInvalid("지급대행 요청 거절 " + code);
        if (!inquiry && status == 422 && "INVALID_IDEMPOTENCY_KEY".equals(code))
            return new PayoutAgencyResult.KeyConflict("지급대행 멱등 키 충돌");
        return new PayoutAgencyResult.Unknown("지급대행 응답 " + status + (code.isEmpty() ? "" : " " + code));
    }

    private PayoutAgencyResult observed(JsonNode node) {
        AgencyStatus status = PgJson.safely(
                        () -> AgencyStatus.valueOf(node.path("status").asText()))
                .orElse(null);
        Long amount =
                node.path("amount").isIntegralNumber() ? node.path("amount").longValue() : null;
        return new PayoutAgencyResult.Observed(
                status,
                text(node, "payoutId"),
                text(node, "idempotencyKey"),
                text(node, "accountRef"),
                amount,
                text(node, "failureCode"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
