package com.sparta.ticket.payment.infrastructure.pg.toss;

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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
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
 * 토스페이먼츠 결제 API 어댑터. 경로·필드는 토스 공식 문서 형식이고, 로컬·테스트에서는 fake PG가 같은 형식으로 응답한다.
 * 결제창은 클라이언트가 띄우므로 준비는 PG를 부르지 않는다. 승인·취소는 Idempotency-Key를 보낸다.
 */
@Component
public class TossPgClient implements PgClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * 처리됐을 수 있어 거절로 볼 수 없는 4xx. "이미 처리됨"은 앞선 승인이 성공했을 수 있고, 멱등 키 처리 중·불일치는
     * 같은 키의 다른 요청이 진행 중이거나 이미 끝났다는 뜻이다.
     */
    private static final Set<String> UNKNOWN_CODES =
            Set.of("ALREADY_PROCESSED_PAYMENT", "IDEMPOTENT_REQUEST_PROCESSING", "INVALID_IDEMPOTENCY_KEY");

    private static final Set<String> NOT_APPROVED_STATUSES =
            Set.of("READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT", "ABORTED", "EXPIRED");

    private final RestClient restClient;
    private final MeterRegistry meters;
    private final ObjectMapper objectMapper;
    private final String authorization;

    public TossPgClient(RestClient.Builder builder, PgProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, null);
    }

    @Autowired
    public TossPgClient(
            RestClient.Builder builder, PgProperties properties, ObjectMapper objectMapper, MeterRegistry meters) {
        this.meters = meters;
        this.restClient = PgHttp.restClient(builder, properties.toss().baseUrl(), properties);
        this.objectMapper = objectMapper;
        this.authorization = "Basic "
                + Base64.getEncoder()
                        .encodeToString((properties.toss().secretKey() + ":").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PgProvider provider() {
        return PgProvider.TOSS;
    }

    /** 토스는 결제창에 orderId·amount만 넘기면 되므로 PG를 부르지 않는다. */
    @Override
    public PgReadyResult ready(PgReadyRequest request) {
        return new PgReadyResult.Ready(null, null);
    }

    @Override
    public PgResult approve(PgApproveRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentKey", request.authToken());
        body.put("orderId", request.orderId());
        body.put("amount", request.amount());
        PgCall call = PgHttp.call(
                client -> client.post()
                        .uri("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(IDEMPOTENCY_KEY, request.orderId() + ":confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                restClient,
                meters,
                "TOSS",
                "approve");
        return result(
                call,
                json -> new PgResult.Approved(
                        json.path("paymentKey").asText(),
                        json.path("totalAmount").asInt(),
                        time(json.path("approvedAt"))));
    }

    @Override
    public PgInquiry inquire(PgInquireRequest request) {
        PgCall call = PgHttp.call(
                client -> client.get()
                        .uri("/v1/payments/orders/{orderId}", request.orderId())
                        .header(HttpHeaders.AUTHORIZATION, authorization),
                restClient,
                meters,
                "TOSS",
                "inquire");
        if (!(call instanceof PgCall.Responded responded)) {
            return new PgInquiry.Unknown(call.toString());
        }
        Optional<JsonNode> json = PgJson.parse(objectMapper, responded.body());
        if (responded.isSuccessful() && json.isPresent()) {
            return PgJson.safely(() -> inquiry(json.get(), request))
                    .orElseGet(() -> new PgInquiry.Unknown("해석할 수 없는 조회 응답"));
        }
        if (responded.status() == 404
                && json.map(j -> j.path("code").asText()).orElse("").equals("NOT_FOUND_PAYMENT")) {
            return new PgInquiry.NotFound();
        }
        return new PgInquiry.Unknown("토스 조회 응답 " + responded.status());
    }

    /**
     * 같은 Idempotency-Key로 다시 보내면 토스가 처음 응답을 재현한다. 키가 다른데 이미 취소된 결제면 ALREADY_CANCELED_PAYMENT가
     * 오므로, 조회해 취소가 확인되면 성공으로 돌려준다.
     */
    @Override
    public PgResult cancel(PgCancelRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cancelReason", request.reason());
        body.put("cancelAmount", request.amount());
        PgCall call = PgHttp.call(
                client -> client.post()
                        .uri("/v1/payments/{paymentKey}/cancel", request.transactionId())
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(IDEMPOTENCY_KEY, request.idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                restClient,
                meters,
                "TOSS",
                "cancel");
        if (call instanceof PgCall.Responded responded
                && errorCode(responded)
                        .filter("ALREADY_CANCELED_PAYMENT"::equals)
                        .isPresent()) {
            return confirmCanceled(request);
        }
        PgResult outcome = result(call, json -> cancellation(json, request));
        if (outcome instanceof PgResult.Rejected rejected
                && !Set.of(
                                "PG_BUSY",
                                "PG_UNAVAILABLE",
                                "NOT_CANCELABLE_PAYMENT",
                                "UNAUTHORIZED_KEY",
                                "INVALID_REQUEST",
                                "NOT_FOUND_PAYMENT",
                                "REJECT_CARD_PAYMENT")
                        .contains(rejected.code())) {
            return new PgResult.Unknown("PG_UNCLASSIFIED_CANCEL_REJECTION");
        }
        return outcome;
    }

    private PgResult cancellation(JsonNode json, PgCancelRequest request) {
        JsonNode cancel = json.path("cancels").path(0);
        if (!request.transactionId().equals(json.path("paymentKey").asText())
                || !request.orderId().equals(json.path("orderId").asText())
                || !"CANCELED".equals(json.path("status").asText())
                || json.path("balanceAmount").asInt(-1) != 0
                || cancel.path("cancelAmount").asInt(-1) != request.amount()
                || cancel.path("transactionKey").asText().isBlank()) {
            return new PgResult.Unknown("PG_INVALID_CANCELLATION");
        }
        return new PgResult.Approved(
                cancel.path("transactionKey").asText(),
                cancel.path("cancelAmount").asInt(),
                time(cancel.path("canceledAt")));
    }

    private PgResult confirmCanceled(PgCancelRequest request) {
        PgInquiry inquiry = inquire(new PgInquireRequest(request.orderId(), request.transactionId()));
        if (inquiry instanceof PgInquiry.Canceled canceled
                && request.transactionId().equals(canceled.transactionId())
                && canceled.amount() == request.amount()
                && canceled.remainingAmount() == 0
                && canceled.cancelId() != null
                && !canceled.cancelId().isBlank()) {
            return new PgResult.Approved(canceled.cancelId(), canceled.amount(), canceled.canceledAt());
        }
        return new PgResult.Unknown(
                inquiry instanceof PgInquiry.Canceled || inquiry instanceof PgInquiry.Inconsistent
                        ? "PG_INVALID_CANCELLATION"
                        : "PG_CANCEL_NOT_CONFIRMED");
    }

    /** 승인·취소 응답 분류. 공통 규칙 → 2xx 성공 → 처리됐을 수 있는 4xx는 Unknown → 그 밖의 4xx는 업무 거절. */
    private PgResult result(PgCall call, Function<JsonNode, PgResult> success) {
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
        if (code.isEmpty() || UNKNOWN_CODES.contains(code.get())) {
            return new PgResult.Unknown("토스 응답 " + responded.status() + " " + code.orElse(""));
        }
        String message = PgJson.parse(objectMapper, responded.body())
                .map(j -> j.path("message").asText())
                .orElse("");
        return new PgResult.Rejected(code.get(), message);
    }

    private PgInquiry inquiry(JsonNode json, PgInquireRequest request) {
        String status = json.path("status").asText();
        if (!request.orderId().equals(json.path("orderId").asText())
                || request.transactionId() != null
                        && !request.transactionId()
                                .equals(json.path("paymentKey").asText())) {
            return new PgInquiry.Inconsistent("PG_TRANSACTION_MISMATCH");
        }
        if ("PARTIAL_CANCELED".equals(status)) return new PgInquiry.Inconsistent("PG_PARTIAL_CANCELLATION");
        if ("DONE".equals(status)) {
            if (json.path("balanceAmount").asInt(-1) != json.path("totalAmount").asInt(-2))
                return new PgInquiry.Inconsistent("PG_REMAINING_AMOUNT_MISMATCH");
            return new PgInquiry.Approved(
                    json.path("paymentKey").asText(), json.path("totalAmount").asInt(), time(json.path("approvedAt")));
        }
        if ("CANCELED".equals(status)) {
            return new PgInquiry.Canceled(
                    json.path("paymentKey").asText(),
                    time(json.path("cancels").path(0).path("canceledAt")),
                    json.path("cancels").path(0).path("cancelAmount").asInt(-1),
                    json.path("balanceAmount").asInt(-1),
                    json.path("cancels").path(0).path("transactionKey").asText(null));
        }
        if (NOT_APPROVED_STATUSES.contains(status)) {
            return new PgInquiry.NotFound();
        }
        return new PgInquiry.Unknown("토스 결제 상태 " + status);
    }

    private Optional<String> errorCode(PgCall.Responded responded) {
        if (responded.isSuccessful()) {
            return Optional.empty();
        }
        return PgJson.parse(objectMapper, responded.body())
                .map(j -> j.path("code").asText(null));
    }

    /** 토스 시각은 +09:00 오프셋이 붙은 ISO 8601이다. DB는 KST LocalDateTime으로 저장한다. */
    private static LocalDateTime time(JsonNode node) {
        return node.isTextual() ? OffsetDateTime.parse(node.asText()).toLocalDateTime() : null;
    }
}
