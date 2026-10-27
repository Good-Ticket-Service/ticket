package com.sparta.ticket.payment.infrastructure.pg;

import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * PG HTTP 호출의 공통 처리. 응답 상태를 예외로 바꾸지 않고 그대로 돌려주고, 전송 실패는 "요청을 보내기 전 실패"와
 * "보낸 뒤 실패"로 나눈다.
 *
 * Note: 팀 서비스 간 호출은 Feign을 쓰지만 PG는 RestClient로 부른다. 팀 공통 Feign 설정은 모든 Feign 클라이언트에
 * X-User-* 헤더를 붙이고 오류 응답을 BusinessException으로 바꾸므로, 외부 PG에 내부 헤더가 새고 PG 원본 오류 코드가 사라진다.
 * 또 Feign은 연결 실패와 읽기 타임아웃을 같은 예외로 감싸지만, JDK HttpClient는 ConnectException·HttpConnectTimeoutException
 * (보내기 전)과 HttpTimeoutException(보낸 뒤)을 예외 타입으로 구분해 준다. 자동 재시도도 하지 않는다.
 */
public final class PgHttp {

    private PgHttp() {}

    public static RestClient restClient(RestClient.Builder builder, String baseUrl, PgProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /** 요청을 실행해 상태와 본문을 받는다. 4xx·5xx도 예외 없이 Responded로 돌려준다. */
    public static PgCall call(Function<RestClient, RestClient.RequestHeadersSpec<?>> request, RestClient client) {
        try {
            return request.apply(client)
                    .exchange((req, res) ->
                            new PgCall.Responded(res.getStatusCode().value(), readBody(res.getBody())));
        } catch (ResourceAccessException e) {
            return classify(e);
        }
    }

    /**
     * PG와 무관하게 정해지는 승인·취소 결과(plan.md 6절 분류표). 연결 실패는 Rejected(PG_UNAVAILABLE), 429는
     * Rejected(PG_BUSY), 보낸 뒤 실패와 5xx는 Unknown이다. 그 밖(2xx, 4xx 업무 응답)은 비워 두고 어댑터가 판단한다.
     *
     * Note: 429를 Rejected로 두는 것은 fake PG가 "처리 한도 초과는 처리하지 않는다"를 보장하기 때문이다. 실제 PG의 429가
     * 같은 보장을 하는지는 샌드박스로 확인하기 전까지 모른다(plan.md 10절).
     */
    public static Optional<PgResult> transportResult(PgCall call) {
        if (call instanceof PgCall.NotSent notSent) {
            return Optional.of(new PgResult.Rejected(PaymentFailureCode.PG_UNAVAILABLE.name(), notSent.reason()));
        }
        if (call instanceof PgCall.SentButFailed failed) {
            return Optional.of(new PgResult.Unknown(failed.reason()));
        }
        int status = ((PgCall.Responded) call).status();
        if (status == 429) {
            return Optional.of(new PgResult.Rejected(PaymentFailureCode.PG_BUSY.name(), "PG 처리 한도 초과"));
        }
        if (status >= 500) {
            return Optional.of(new PgResult.Unknown("PG 응답 " + status));
        }
        return Optional.empty();
    }

    static PgCall classify(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
            return new PgCall.NotSent(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        String reason = cause == null ? e.getMessage() : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new PgCall.SentButFailed(reason);
    }

    private static String readBody(InputStream body) throws IOException {
        return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** PG 호출 한 번의 전송 결과. 업무 의미(승인·거절)는 어댑터가 정한다. */
    public sealed interface PgCall {

        /** PG가 HTTP 응답을 돌려줬다. 상태가 2xx가 아닐 수 있다. */
        record Responded(int status, String body) implements PgCall {

            public boolean isSuccessful() {
                return status >= 200 && status < 300;
            }
        }

        /** 연결을 맺지 못해 요청을 보내지 못했다. PG는 처리하지 않았다. */
        record NotSent(String reason) implements PgCall {}

        /** 요청을 보냈을 수 있는데 응답을 받지 못했다(읽기 타임아웃, 연결 끊김). PG가 처리했는지 모른다. */
        record SentButFailed(String reason) implements PgCall {}
    }
}
