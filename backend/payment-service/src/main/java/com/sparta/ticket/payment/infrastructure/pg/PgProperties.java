package com.sparta.ticket.payment.infrastructure.pg;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PG 연결 설정({@code pg.*}). 기본값은 로컬 fake PG(포트 8090)와 fake 키다. Compose와 실제 샌드박스는
 * config-repo의 환경 변수로 base-url과 키만 바꾼다.
 *
 * Note: 기본값을 코드에 두는 이유는 통합 테스트(PostgresIntegrationTest)가 Config Server를 끄고 뜨기 때문이다.
 * 그래도 PG 어댑터가 만들어져야 컨텍스트가 뜬다. 기본값은 모두 fake PG용이라 실제 키가 아니다.
 *
 * @param connectTimeout TCP 연결 제한 시간. 넘으면 요청을 보내지 못한 것이므로 Rejected(PG_UNAVAILABLE)
 * @param readTimeout 요청을 보낸 뒤 응답을 기다리는 시간. 넘으면 PG가 처리했을 수 있으므로 Unknown
 */
@ConfigurationProperties("pg")
public record PgProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue Toss toss,
        @DefaultValue Kakao kakao) {

    /** @param secretKey Basic 인증에 쓰는 시크릿 키 */
    public record Toss(
            @DefaultValue("http://localhost:8090") String baseUrl,
            @DefaultValue("test_sk_fake_toss") String secretKey) {}

    /**
     * @param cid 가맹점 코드
     * @param approvalUrl 사용자가 결제창 인증을 마치면 카카오가 pg_token을 붙여 돌려보내는 클라이언트 주소
     */
    public record Kakao(
            @DefaultValue("http://localhost:8090") String baseUrl,
            @DefaultValue("DEV_fake_kakao") String secretKey,
            @DefaultValue("TC0ONETIME") String cid,

            @DefaultValue("http://localhost:8080/payments/kakao/approve")
            String approvalUrl,

            @DefaultValue("http://localhost:8080/payments/kakao/cancel")
            String cancelUrl,

            @DefaultValue("http://localhost:8080/payments/kakao/fail")
            String failUrl) {}
}
