package com.sparta.ticket.payment.infrastructure.pg.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.pg.PgHttp;
import com.sparta.ticket.payment.infrastructure.pg.PgHttp.PgCall;
import com.sparta.ticket.payment.infrastructure.pg.PgJson;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 카카오페이 단건 결제 API 어댑터. 토스와 달리 결제창을 띄우기 전에 준비(tid 발급)를 부르고, 승인에는 금액 대신 pg_token을 보낸다.
 *
 * Note: 카카오에는 멱등 키가 없다. 응답을 잃은 승인·취소를 다시 보내면 재현이 아니라 "이미 처리됨" 오류가 온다. 그래서 승인의
 * 이미 처리됨은 Unknown으로 두어 복구가 조회로 확정하게 하고, 취소는 보내기 전에 조회해 이미 취소됐으면 성공으로 돌려준다.
 * 오류 코드는 fake PG가 정한 대응(docs/api.md의 Fake PG 계약)이며 샌드박스 검증 때 맞춘다.
 */
@Component
public class KakaoPgClient implements PgClient {

    /** 승인 요청을 PG가 처리하지 않았다고 확정할 수 있는 오류. 목록 밖의 코드는 처리됐을 수 있어 Unknown이다. */
    private static final Set<String> APPROVE_REJECTED_CODES = Set.of("-2", "-401", "-701", "-703", "-780");

    private static final Set<String> CANCEL_REJECTED_CODES = Set.of("-2", "-401", "-704", "-722");

    private static final String ALREADY_CANCELED = "-721";
    private static final String NOT_FOUND = "-704";

    private static final Set<String> NOT_APPROVED_STATUSES = Set.of(
            "READY",
            "SEND_TMS",
            "OPEN_PAYMENT",
            "SELECT_METHOD",
            "ARS_WAITING",
            "AUTH_PASSWORD",
            "ISSUED_SID",
            "FAIL_AUTH_PASSWORD",
            "QUIT_PAYMENT",
            "FAIL_PAYMENT");

    private final RestClient restClient;
    private final MeterRegistry meters;
    private final ObjectMapper objectMapper;
    private final PgProperties.Kakao kakao;
    private final String authorization;

    public KakaoPgClient(RestClient.Builder builder, PgProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, null);
    }

    @Autowired
    public KakaoPgClient(
            RestClient.Builder builder, PgProperties properties, ObjectMapper objectMapper, MeterRegistry meters) {
        this.meters = meters;
        this.restClient = PgHttp.restClient(builder, properties.kakao().baseUrl(), properties);
        this.objectMapper = objectMapper;
        this.kakao = properties.kakao();
        this.authorization = "SECRET_KEY " + kakao.secretKey();
    }

    @Override
    public PgProvider provider() {
        return PgProvider.KAKAO;
    }

    /** 준비는 돈이 오가지 않으므로 거절과 결과 불명을 나누지 않는다. 성공 응답이 아니면 모두 Failed다. */
    @Override
    public PgReadyResult ready(PgReadyRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cid", kakao.cid());
        body.put("partner_order_id", request.orderId());
        body.put("partner_user_id", String.valueOf(request.userId()));
        body.put("item_name", request.itemName());
        body.put("quantity", 1);
        body.put("total_amount", request.amount());
        body.put("tax_free_amount", 0);
        body.put("approval_url", kakao.approvalUrl());
        body.put("cancel_url", kakao.cancelUrl());
        body.put("fail_url", kakao.failUrl());
        PgCall call = post("/online/v1/payment/ready", body);
        if (call instanceof PgCall.Responded responded && responded.isSuccessful()) {
            Optional<PgReadyResult> ready = PgJson.parse(objectMapper, responded.body())
                    .filter(json -> json.hasNonNull("tid"))
                    .map(json -> new PgReadyResult.Ready(
                            json.path("tid").asText(),
                            json.path("next_redirect_pc_url").asText(null)));
            if (ready.isPresent()) {
                return ready.get();
            }
        }
        return new PgReadyResult.Failed("카카오 준비 실패: " + call);
    }

    @Override
    public PgResult approve(PgApproveRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cid", kakao.cid());
        body.put("tid", request.transactionId());
        body.put("partner_order_id", request.orderId());
        body.put("partner_user_id", String.valueOf(request.userId()));
        body.put("pg_token", request.authToken());
        return result(
                post("/online/v1/payment/approve", body),
                APPROVE_REJECTED_CODES,
                json -> new PgResult.Approved(
                        json.path("tid").asText(),
                        json.path("amount").path("total").asInt(),
                        time(json.path("approved_at"))));
    }

    /** 카카오는 tid로만 조회한다. 준비 결과를 저장하기 전에 중단돼 tid가 없으면 카카오에 승인 기록도 없다. */
    @Override
    public PgInquiry inquire(PgInquireRequest request) {
        if (request.transactionId() == null) {
            return new PgInquiry.NotFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cid", kakao.cid());
        body.put("tid", request.transactionId());
        PgCall call = post("/online/v1/payment/order", body);
        if (!(call instanceof PgCall.Responded responded)) {
            return new PgInquiry.Unknown(call.toString());
        }
        Optional<JsonNode> json = PgJson.parse(objectMapper, responded.body());
        if (responded.isSuccessful() && json.isPresent()) {
            return PgJson.safely(() -> inquiry(json.get(), request))
                    .orElseGet(() -> new PgInquiry.Unknown("해석할 수 없는 조회 응답"));
        }
        if (errorCode(responded).filter(NOT_FOUND::equals).isPresent()) {
            return new PgInquiry.NotFound();
        }
        return new PgInquiry.Unknown("카카오 조회 응답 " + responded.status());
    }

    /**
     * 먼저 조회해 이미 취소됐으면 PG를 다시 부르지 않고 성공으로 돌려준다. 조회와 취소 사이에 다른 취소가 끝나 "이미 취소됨"이
     * 오면 한 번 더 조회해 확인한다.
     */
    @Override
    public PgResult cancel(PgCancelRequest request) {
        Optional<PgResult> alreadyCanceled = canceledResult(request);
        if (alreadyCanceled.isPresent()) {
            return alreadyCanceled.get();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cid", kakao.cid());
        body.put("tid", request.transactionId());
        body.put("cancel_amount", request.amount());
        body.put("cancel_tax_free_amount", 0);
        PgCall call = post("/online/v1/payment/cancel", body);
        if (call instanceof PgCall.Responded responded
                && errorCode(responded).filter(ALREADY_CANCELED::equals).isPresent()) {
            return canceledResult(request).orElseGet(() -> new PgResult.Unknown("이미 취소됨 응답 후 조회로 취소를 확인하지 못했다"));
        }
        return result(call, CANCEL_REJECTED_CODES, json -> {
            if (!request.transactionId().equals(json.path("tid").asText())
                    || !"CANCEL_PAYMENT".equals(json.path("status").asText())
                    || json.path("cancel_available_amount").path("total").asInt(-1) != 0
                    || json.path("canceled_amount").path("total").asInt(-1) != request.amount()) {
                return new PgResult.Unknown("PG_INVALID_CANCELLATION");
            }
            return new PgResult.Approved(
                    json.path("tid").asText(),
                    json.path("canceled_amount").path("total").asInt(),
                    time(json.path("canceled_at")));
        });
    }

    private Optional<PgResult> canceledResult(PgCancelRequest request) {
        PgInquiry inquiry = inquire(new PgInquireRequest(request.orderId(), request.transactionId()));
        if (inquiry instanceof PgInquiry.Canceled canceled) {
            if (!request.transactionId().equals(canceled.transactionId())
                    || canceled.amount() != request.amount()
                    || canceled.remainingAmount() != 0)
                return Optional.of(new PgResult.Unknown("PG_INVALID_CANCELLATION"));
            return Optional.of(new PgResult.Approved(canceled.cancelId(), canceled.amount(), canceled.canceledAt()));
        }
        if (inquiry instanceof PgInquiry.Inconsistent)
            return Optional.of(new PgResult.Unknown("PG_INVALID_CANCELLATION"));
        return Optional.empty();
    }

    private PgCall post(String path, Map<String, Object> body) {
        return PgHttp.call(
                client -> client.post()
                        .uri(path)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                restClient,
                meters,
                "KAKAO",
                path.substring(path.lastIndexOf('/') + 1).replace("order", "inquire"));
    }

    /** 승인·취소 응답 분류. 공통 규칙 → 2xx 성공 → 처리하지 않았음이 확실한 코드만 업무 거절 → 그 밖은 Unknown. */
    private PgResult result(PgCall call, Set<String> rejectedCodes, Function<JsonNode, PgResult> success) {
        Optional<PgResult> common = PgHttp.transportResult(call);
        if (common.isPresent()) {
            return common.get();
        }
        PgCall.Responded responded = (PgCall.Responded) call;
        if (responded.isSuccessful()) {
            return PgJson.parse(objectMapper, responded.body())
                    .flatMap(json -> PgJson.safely(() -> success.apply(json)))
                    .orElseGet(() -> new PgResult.Unknown("해석할 수 없는 성공 응답"));
        }
        Optional<String> code = errorCode(responded);
        if (code.isEmpty() || !rejectedCodes.contains(code.get())) {
            return new PgResult.Unknown("카카오 응답 " + responded.status() + " " + code.orElse(""));
        }
        String message = PgJson.parse(objectMapper, responded.body())
                .map(json -> json.path("error_message").asText())
                .orElse("");
        return new PgResult.Rejected(code.get(), message);
    }

    private PgInquiry inquiry(JsonNode json, PgInquireRequest request) {
        String status = json.path("status").asText();
        if (!request.transactionId().equals(json.path("tid").asText())
                || !request.orderId().equals(json.path("partner_order_id").asText())) {
            return new PgInquiry.Inconsistent("PG_TRANSACTION_MISMATCH");
        }
        if ("PART_CANCEL_PAYMENT".equals(status)) return new PgInquiry.Inconsistent("PG_PARTIAL_CANCELLATION");
        if ("SUCCESS_PAYMENT".equals(status)) {
            if (json.path("cancel_available_amount").path("total").asInt(-1)
                    != json.path("amount").path("total").asInt(-2))
                return new PgInquiry.Inconsistent("PG_REMAINING_AMOUNT_MISMATCH");
            return new PgInquiry.Approved(
                    json.path("tid").asText(),
                    json.path("amount").path("total").asInt(),
                    time(json.path("approved_at")));
        }
        if ("CANCEL_PAYMENT".equals(status)) {
            return new PgInquiry.Canceled(
                    json.path("tid").asText(),
                    time(json.path("canceled_at")),
                    json.path("canceled_amount").path("total").asInt(-1),
                    json.path("cancel_available_amount").path("total").asInt(-1),
                    json.path("tid").asText(null));
        }
        if (NOT_APPROVED_STATUSES.contains(status)) {
            return new PgInquiry.NotFound();
        }
        return new PgInquiry.Unknown("카카오 결제 상태 " + status);
    }

    private Optional<String> errorCode(PgCall.Responded responded) {
        if (responded.isSuccessful()) {
            return Optional.empty();
        }
        return PgJson.parse(objectMapper, responded.body())
                .filter(json -> json.hasNonNull("error_code"))
                .map(json -> json.path("error_code").asText());
    }

    /** 카카오 시각은 오프셋 없는 한국 시간이다. */
    private static LocalDateTime time(JsonNode node) {
        return node.isTextual() ? LocalDateTime.parse(node.asText()) : null;
    }
}
