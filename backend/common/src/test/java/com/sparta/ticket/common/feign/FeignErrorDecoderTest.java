package com.sparta.ticket.common.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeignErrorDecoderTest {

    private final FeignErrorDecoder decoder = new FeignErrorDecoder(new ObjectMapper());

    private static Response response(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "http://company-service/internal/companies/1",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().request(request).status(status)
                .body(body, StandardCharsets.UTF_8).headers(Map.of()).build();
    }

    @Test
    @DisplayName("하위 서비스의 4xx 공통 에러는 상태·코드·문구를 그대로 전달한다")
    void propagatesDomainError() {
        Exception e = decoder.decode("CompanyClient#get", response(404,
                "{\"status\":404,\"code\":\"EVENT_NOT_FOUND\",\"message\":\"공연을 찾을 수 없습니다.\"}"));

        BusinessException business = (BusinessException) e;
        assertThat(business.getErrorCode().status().value()).isEqualTo(404);
        assertThat(business.getErrorCode().code()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(business.getMessage()).isEqualTo("공연을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("5xx와 형식을 알 수 없는 응답은 503 SERVICE_UNAVAILABLE")
    void serverErrorBecomesUnavailable() {
        Exception serverError = decoder.decode("CompanyClient#get", response(500, "{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"x\"}"));
        Exception unknownBody = decoder.decode("CompanyClient#get", response(400, "<html>bad</html>"));

        assertThat(((BusinessException) serverError).getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThat(((BusinessException) unknownBody).getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
