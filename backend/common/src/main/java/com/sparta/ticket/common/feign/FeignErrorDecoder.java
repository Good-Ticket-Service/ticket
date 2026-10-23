package com.sparta.ticket.common.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.exception.SimpleErrorCode;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;

/**
 * 하위 서비스의 에러 응답을 호출한 서비스의 예외로 바꾼다.
 * Note: 하위 서비스도 공통 에러 형식(status, code, message)으로 응답하므로, 4xx는 그 코드를 그대로 BusinessException에 담아
 * 올려 보낸다. 예를 들어 reservation이 event를 불러 404 EVENT_NOT_FOUND를 받으면 클라이언트도 같은 코드를 받는다.
 * 5xx나 형식을 알 수 없는 응답은 하위 서비스 장애로 보고 503 SERVICE_UNAVAILABLE로 바꾼다.
 */
@Slf4j
public class FeignErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;

    public FeignErrorDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        HttpStatusCode status = HttpStatusCode.valueOf(response.status());
        if (status.is4xxClientError()) {
            JsonNode body = readBody(response);
            if (body != null && body.hasNonNull("code") && body.hasNonNull("message")) {
                return new BusinessException(new SimpleErrorCode(
                        status, body.get("code").asText(), body.get("message").asText()));
            }
        }
        log.warn("하위 서비스 호출 실패: {} status={}", methodKey, response.status());
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private JsonNode readBody(Response response) {
        if (response.body() == null) {
            return null;
        }
        try (InputStream body = response.body().asInputStream()) {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }
}
