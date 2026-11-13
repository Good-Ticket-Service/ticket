package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer;
import com.sparta.ticket.reservation.booking.application.port.SubstituteConfirmPort;
import feign.Response;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대체 승인과 그 뒤 결제 조회를 payment 내부 API로 부른다(#422). HTTP 상태, 오류 코드, 상태 이름은 이 클래스 밖으로 나가지 않는다.
 * 호출이 끝나지 않았거나 본문을 읽지 못하면 예외 대신 답을 모름으로 돌려준다.
 */
@Component
@RequiredArgsConstructor
class PaymentSubstituteConfirmAdapter implements SubstituteConfirmPort {

    private static final int OK = 200;
    private static final int SERVICE_UNAVAILABLE = 503;

    private final SubstituteConfirmClient client;
    private final ObjectMapper objectMapper;
    private final PaymentConfirmSettings settings;

    @Override
    public SubstituteAnswer confirm(PaymentConfirmRequest request) {
        return call(
                () -> client.confirm(request.orderId(), SubstituteConfirmRequest.from(request)), this::confirmAnswer);
    }

    @Override
    public SubstituteAnswer lookup(PaymentConfirmRequest request) {
        return call(() -> client.find(request.orderId()), this::lookupAnswer);
    }

    private SubstituteAnswer call(Supplier<Response> send, OkReader onOk) {
        try (Response response = send.get()) {
            int status = response.status();
            if (status == OK) {
                return onOk.read(response);
            }
            if (status == SERVICE_UNAVAILABLE) {
                return new SubstituteAnswer.Busy(retryAfter(response));
            }
            // 409 지문 충돌, 400 금액 불일치, 404 주문 없음과 모르는 4xx는 같은 요청을 다시 보내도 같은 답이다
            if (status >= 400 && status < 500) {
                return new SubstituteAnswer.Rejected(errorCode(response));
            }
            return new SubstituteAnswer.Unknown(new IllegalStateException("payment가 " + status + "로 답했다"));
        } catch (RuntimeException | IOException e) {
            return new SubstituteAnswer.Unknown(e);
        }
    }

    /** IN_PROGRESS와 READY는 받았지만 결과가 정해지지 않았다. FAILED에는 시한이 지나 PG를 부르지 않은 EXPIRED도 들어 있다. */
    private SubstituteAnswer confirmAnswer(Response response) throws IOException {
        ConfirmBody body = read(response, ConfirmBody.class);
        return switch (String.valueOf(body.status())) {
            case "DONE" -> new SubstituteAnswer.Settled(ConfirmOutcome.succeeded());
            case "FAILED" -> new SubstituteAnswer.Settled(ConfirmOutcome.failed());
            case "IN_PROGRESS", "READY" -> new SubstituteAnswer.Accepted();
            default -> unknownStatus(body.status());
        };
    }

    /**
     * READY는 승인 접수 시각이 있으면 받은 뒤 차례를 기다리는 중이고, 없으면 승인이 닿지 않은 것이다. 승인된 뒤 전액 환불된 CANCELED는
     * 돈이 사용자에게 돌아갔으므로 결과 확인처럼 실패로 반영한다.
     */
    private SubstituteAnswer lookupAnswer(Response response) throws IOException {
        LookupBody body = read(response, LookupBody.class);
        return switch (String.valueOf(body.status())) {
            case "DONE" -> new SubstituteAnswer.Settled(ConfirmOutcome.succeeded());
            case "FAILED", "CANCELED" -> new SubstituteAnswer.Settled(ConfirmOutcome.failed());
            case "IN_PROGRESS" -> new SubstituteAnswer.Accepted();
            case "READY" ->
                body.confirmRequestedAt() == null
                        ? new SubstituteAnswer.NotReceived()
                        : new SubstituteAnswer.Accepted();
            default -> unknownStatus(body.status());
        };
    }

    private static SubstituteAnswer unknownStatus(String status) {
        return new SubstituteAnswer.Unknown(new IllegalStateException("알 수 없는 결제 상태다. status=" + status));
    }

    /** Retry-After는 초 단위 정수다(#422). 없거나 읽지 못하면 작업자가 다시 볼 간격만큼 쉬게 한다. */
    private Duration retryAfter(Response response) {
        Collection<String> values = response.headers().get("Retry-After");
        if (values != null) {
            for (String value : values) {
                try {
                    long seconds = Long.parseLong(value.trim());
                    if (seconds >= 0) {
                        return Duration.ofSeconds(seconds);
                    }
                } catch (NumberFormatException ignored) {
                    // 날짜 형식이나 잘못된 값은 없는 것으로 본다
                }
            }
        }
        return settings.drain().resendAfter();
    }

    /** 공통 오류 형식(status, code, message)의 code. 형식이 다르면 HTTP 상태로 남긴다. */
    private String errorCode(Response response) {
        try {
            JsonNode body = read(response, JsonNode.class);
            if (body.hasNonNull("code")) {
                return body.get("code").asText();
            }
        } catch (IOException ignored) {
            // 코드를 읽지 못해도 4xx라 받지 않는다는 답은 같다
        }
        return "HTTP " + response.status();
    }

    private <T> T read(Response response, Class<T> type) throws IOException {
        if (response.body() == null) {
            throw new IOException("payment 응답에 본문이 없다");
        }
        try (InputStream body = response.body().asInputStream()) {
            return objectMapper.readValue(body, type);
        }
    }

    @FunctionalInterface
    private interface OkReader {
        SubstituteAnswer read(Response response) throws IOException;
    }

    record ConfirmBody(String status) {}

    record LookupBody(String status, OffsetDateTime confirmRequestedAt) {}
}
